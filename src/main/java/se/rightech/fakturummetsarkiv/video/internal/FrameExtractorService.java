package se.rightech.fakturummetsarkiv.video.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.TessBaseAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.bytedeco.leptonica.global.leptonica.pixDestroy;
import static org.bytedeco.leptonica.global.leptonica.pixReadMem;

@Service
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class FrameExtractorService {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractorService.class);

    private static final int DIFF_SIZE = 32;
    // Mellansteg vid nedskalning till diff-signaturen, annars blir 1080p -> 32x32 brusigt.
    private static final int DIFF_PRESCALE = 256;

    @Value("${video.max-frames:300}")
    private int maxFrames;

    /** Största sidan (px) på bilden som skickas till modellen. */
    @Value("${video.max-dim:768}")
    private int maxDim;

    /** Största sidan (px) på bilden som OCR körs på. Större än max-dim ger bättre textläsning. */
    @Value("${video.ocr-max-dim:1600}")
    private int ocrMaxDim;

    @Value("${video.frame-diff-threshold:3.0}")
    private double frameDiffThreshold;

    @Value("${video.max-static-seconds:3}")
    private int maxStaticSeconds;

    @Value("${video.ocr-enabled:true}")
    private boolean ocrEnabled;

    /** Tesseract-språk. Kräver traineddata för varje språk (TESSDATA_PREFIX). */
    @Value("${video.ocr-lang:eng+swe}")
    private String ocrLang;

    /** Tak för antal tecken OCR-text per bildruta, så prompten inte sväller. */
    @Value("${video.ocr-max-chars-per-frame:500}")
    private int ocrMaxChars;

    private final BufferedImageOp sharpenFilter;

    /**
     * @param base64Image   JPEG (nedskalad + skärpt) för modellen
     * @param extractedText OCR-text från en större version av samma bildruta
     * @param second        sekund i videon som bildrutan hör till
     */
    public record AnalyzedFrame(String base64Image, String extractedText, int second, java.nio.file.Path framePath) {
        public AnalyzedFrame(String base64Image, String extractedText, int second) {
            this(base64Image, extractedText, second, null);
        }
    }

    public FrameExtractorService() {
        float[] sharpenMatrix = {
                0.0f, -1.0f, 0.0f,
                -1.0f, 5.0f, -1.0f,
                0.0f, -1.0f, 0.0f
        };
        this.sharpenFilter = new ConvolveOp(new Kernel(3, 3, sharpenMatrix), ConvolveOp.EDGE_NO_OP, null);
    }

    public List<AnalyzedFrame> extractFrames(File videoFile) throws Exception {
        return extractFrames(videoFile, null);
    }

    public List<AnalyzedFrame> extractFrames(File videoFile, java.nio.file.Path framesDir) throws Exception {
        if (framesDir != null) {
            java.nio.file.Files.createDirectories(framesDir);
        }
        List<AnalyzedFrame> analyzedFrames = new ArrayList<>();
        TessBaseAPI tessApi = initTesseract();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            // Ingen setImageWidth/Height: vi avkodar i originalstorlek och skalar själva
            // med bevarade proportioner (och behåller full upplösning för OCR).
            grabber.start();

            double fps = grabber.getVideoFrameRate();
            int totalSeconds = (int) (grabber.getLengthInTime() / 1_000_000L);
            if (totalSeconds <= 0) {
                totalSeconds = (int) (grabber.getLengthInVideoFrames() / Math.max(1.0, fps));
            }
            log.info("Startar extrahering. Längd: {} s, FPS: {}, max: {} rutor, tröskel: {}",
                    totalSeconds, fps, maxFrames, frameDiffThreshold);

            extractByTimestamp(grabber, totalSeconds, tessApi, analyzedFrames, framesDir);

            if (analyzedFrames.isEmpty()) {
                log.warn("Tidshopp gav inga rutor, faller tillbaka till sekventiell avkodning.");
                try {
                    grabber.restart();
                } catch (Exception e) {
                    log.warn("Kunde inte starta om grabber: {}", e.getMessage());
                }
                extractSequential(grabber, fps, tessApi, analyzedFrames, framesDir);
            }

            grabber.stop();
        } finally {
            closeTesseract(tessApi);
        }

        log.info("Extrahering klar. Behöll {} bildrutor.", analyzedFrames.size());
        return analyzedFrames;
    }

    private void extractByTimestamp(FFmpegFrameGrabber grabber, int totalSeconds,
                                    TessBaseAPI tessApi, List<AnalyzedFrame> out, java.nio.file.Path framesDir) {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage lastSignature = null;
        int lastKeptSecond = -1;
        int nulls = 0;
        int candidates = 0;

        int step = Math.max(1, (int) Math.ceil((double) totalSeconds / maxFrames));

        for (int sec = 0; sec < totalSeconds && out.size() < maxFrames; sec += step) {
            try {
                grabber.setTimestamp(sec * 1_000_000L);
            } catch (Exception e) {
                log.warn("setTimestamp misslyckades vid {}s: {}", sec, e.getMessage());
                return;
            }

            Frame frame;
            try {
                frame = grabber.grabImage();
            } catch (Exception e) {
                continue;
            }
            if (frame == null) {
                if (++nulls > 5) {
                    log.warn("För många null-rutor vid tidshopp, avbryter.");
                    return;
                }
                continue;
            }

            BufferedImage full = converter.convert(frame);
            if (full == null) {
                continue;
            }
            candidates++;

            BufferedImage signature = signature(full);
            boolean forceKeep = lastKeptSecond < 0 || (sec - lastKeptSecond) >= maxStaticSeconds;

            if (lastSignature == null || forceKeep || diff(lastSignature, signature) > frameDiffThreshold) {
                lastSignature = signature;
                lastKeptSecond = sec;
                addFrame(full, sec, tessApi, out, framesDir);
            }
        }
        log.info("Tidshopp: {} kandidater, {} behölls.", candidates, out.size());
    }

    private void extractSequential(FFmpegFrameGrabber grabber, double fps,
                                   TessBaseAPI tessApi, List<AnalyzedFrame> out, java.nio.file.Path framesDir) {
        int totalFrames = grabber.getLengthInVideoFrames();
        double safeFps = fps > 0 ? fps : 1.0;
        int sampleInterval = Math.max(1, (int) Math.ceil(safeFps));

        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage lastSignature = null;
        int lastKeptSecond = -1;

        for (int i = 0; i < totalFrames && out.size() < maxFrames; i++) {
            Frame frame;
            try {
                frame = grabber.grabImage();
            } catch (Exception e) {
                break;
            }
            if (frame == null) {
                break;
            }
            if (i % sampleInterval != 0) {
                continue;
            }

            BufferedImage full = converter.convert(frame);
            if (full == null) {
                continue;
            }

            int sec = (int) (i / safeFps);
            BufferedImage signature = signature(full);
            boolean forceKeep = lastKeptSecond < 0 || (sec - lastKeptSecond) >= maxStaticSeconds;

            if (lastSignature == null || forceKeep || diff(lastSignature, signature) > frameDiffThreshold) {
                lastSignature = signature;
                lastKeptSecond = sec;
                addFrame(full, sec, tessApi, out, framesDir);
            }
        }
    }

    private void addFrame(BufferedImage full, int second, TessBaseAPI tessApi, List<AnalyzedFrame> out,
                          java.nio.file.Path framesDir) {
        if (out.isEmpty()) {
            log.info("Första bildrutan: original {}x{}, till modellen {}x{}",
                    full.getWidth(), full.getHeight(),
                    scaleToMax(full, maxDim).getWidth(), scaleToMax(full, maxDim).getHeight());
        }
        try {
            // Modellbilden: nedskalad med bevarade proportioner, sedan skärpt.
            BufferedImage forModel = sharpenFilter.filter(scaleToMax(full, maxDim), null);
            // OCR körs på en större, oskärpt bild för bästa textläsning.
            String ocrText = runOcr(tessApi, scaleToMax(full, ocrMaxDim), second);

            java.nio.file.Path framePath = null;
            if (framesDir != null) {
                try {
                    java.awt.image.BufferedImage forFrame = scaleToMax(full, ocrMaxDim);
                    String fileName = String.format("frame-%04ds.jpg", second);
                    framePath = framesDir.resolve(fileName);
                    try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(framePath)) {
                        ImageIO.write(forFrame, "jpg", os);
                    }
                } catch (Exception fe) {
                    log.warn("Kunde inte spara bildruta vid {}s: {}", second, fe.getMessage());
                    framePath = null;
                }
            }

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(forModel, "jpg", baos);
                out.add(new AnalyzedFrame(Base64.getEncoder().encodeToString(baos.toByteArray()), ocrText, second, framePath));
            }
        } catch (Exception e) {
            log.warn("Kunde inte bearbeta bildruta vid {}s: {}", second, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- OCR

    private TessBaseAPI initTesseract() {
        if (!ocrEnabled) {
            return null;
        }
        TessBaseAPI api = tryInit(ocrLang);
        if (api == null && !"eng".equals(ocrLang)) {
            log.warn("Tesseract kunde inte starta med '{}', provar 'eng'.", ocrLang);
            api = tryInit("eng");
        }
        if (api == null) {
            log.error("Kunde inte initiera Tesseract OCR (kontrollera TESSDATA_PREFIX). Fortsätter utan OCR.");
        }
        return api;
    }

    private TessBaseAPI tryInit(String lang) {
        TessBaseAPI api = new TessBaseAPI();
        if (api.Init(null, lang) != 0) {
            api.close();
            return null;
        }
        return api;
    }

    private void closeTesseract(TessBaseAPI api) {
        if (api != null) {
            api.End();
            api.close();
        }
    }

    private String runOcr(TessBaseAPI tessApi, BufferedImage img, int second) {
        if (tessApi == null) {
            return "";
        }
        PIX pix = null;
        BytePointer textPtr = null;
        try {
            // Förbehandla: gråskala + binarisering för skarpare text.
            BufferedImage gray = toGrayScale(img);
            BufferedImage binary = binarize(gray);
            BufferedImage processed = scaleToMax(binary, ocrMaxDim);

            // Tesseract vill ha råa pixlar eller en PIX, inte en PNG-fil som bytes.
            // Läs därför in den kodade bilden via Leptonica.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(processed, "png", baos);
            byte[] png = baos.toByteArray();

            pix = pixReadMem(png, png.length);
            if (pix == null) {
                log.warn("Leptonica kunde inte läsa bildruta vid {}s för OCR.", second);
                return "";
            }
            tessApi.SetVariable("preserve_interword_spaces", "1");
            tessApi.SetImage(pix);
            textPtr = tessApi.GetUTF8Text();
            if (textPtr == null) {
                return "";
            }
            return cleanOcr(textPtr.getString());
        } catch (Exception e) {
            log.warn("Tesseract misslyckades vid {}s: {}", second, e.getMessage());
            return "";
        } finally {
            if (textPtr != null) {
                textPtr.deallocate();
            }
            if (pix != null) {
                pixDestroy(pix);
            }
        }
    }

    /** Rensar bort brus: tomma rader, mycket korta rader, extra mellanslag; kapar längden. */
    private String cleanOcr(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String t = line.strip().replaceAll("\\s+", " ");
            if (t.length() < 3) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
            if (sb.length() >= ocrMaxChars) {
                break;
            }
        }
        return sb.length() > ocrMaxChars ? sb.substring(0, ocrMaxChars) : sb.toString();
    }

    // ---------------------------------------------------- bildhjälpmetoder

    private BufferedImage signature(BufferedImage src) {
        return toGrayScale(scaled(scaleToMax(src, DIFF_PRESCALE), DIFF_SIZE, DIFF_SIZE));
    }

    private double diff(BufferedImage ga, BufferedImage gb) {
        double sum = 0.0;
        for (int y = 0; y < DIFF_SIZE; y++) {
            for (int x = 0; x < DIFF_SIZE; x++) {
                sum += Math.abs(ga.getRaster().getSample(x, y, 0) - gb.getRaster().getSample(x, y, 0));
            }
        }
        return sum / (DIFF_SIZE * DIFF_SIZE);
    }

    /** Skalar ned så att längsta sidan blir högst max, med bevarade proportioner. */
    private BufferedImage scaleToMax(BufferedImage src, int max) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= max && h <= max) {
            return src;
        }
        double scale = Math.min((double) max / w, (double) max / h);
        return scaled(src, Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
    }

    private BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private BufferedImage toGrayScale(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private BufferedImage binarize(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }
}
