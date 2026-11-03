package se.rightech.fakturummetsarkiv.video.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.video.VideoAnalysisException;
import se.rightech.fakturummetsarkiv.video.VideoDescription;
import se.rightech.fakturummetsarkiv.video.VideoDescriber;
import se.rightech.fakturummetsarkiv.video.VideoOptions;
import se.rightech.fakturummetsarkiv.video.VideoSegment;

import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoDescriberImpl implements VideoDescriber {

    private static final Logger log = LoggerFactory.getLogger(VideoDescriberImpl.class);

    private static final int SUMMARY_GROUP_SIZE = 8;

    @Value("${video.chunk-size:6}")
    private int chunkSize;

    private final FrameExtractorService frameExtractorService;
    private final VideoOllamaClient videoOllamaClient;
    private final AnalysisResultStore resultStore;

    public VideoDescriberImpl(FrameExtractorService frameExtractorService,
                              VideoOllamaClient videoOllamaClient,
                              AnalysisResultStore resultStore) {
        this.frameExtractorService = frameExtractorService;
        this.videoOllamaClient = videoOllamaClient;
        this.resultStore = resultStore;
    }

    @Override
    public VideoDescription describe(Path videoFile, String originalFilename, VideoOptions options,
                                     Consumer<String> progress) {
        String filename = (originalFilename == null || originalFilename.isBlank())
                ? (videoFile == null ? "video" : videoFile.getFileName().toString())
                : originalFilename;
        String prompt = (options == null || options.prompt() == null || options.prompt().isBlank())
                ? "Describe step by step what happens in this screen recording."
                : options.prompt();
        Consumer<String> sink = (progress == null) ? msg -> { } : progress;

        return processAndAnalyzeVideoFile(filename, videoFile == null ? null : videoFile.toFile(), prompt, sink);
    }

    public VideoDescription processAndAnalyzeVideoFile(String originalFilename, File videoFile, String prompt,
                                                       Consumer<String> progress) {
        ProgressReporter reporter = new ProgressReporter(progress);
        Path runDir = resultStore.startRun(originalFilename);
        long started = System.currentTimeMillis();

        reporter.report("Extraherar bildrutor...");
        List<FrameExtractorService.AnalyzedFrame> frames;
        try {
            frames = frameExtractorService.extractFrames(videoFile);
        } catch (Exception e) {
            throw new VideoAnalysisException("Kunde inte extrahera bildrutor: " + e.getMessage(), e);
        }

        if (frames.isEmpty()) {
            String msg = "Kunde inte hitta några läsbara bildrutor i videofilen.";
            resultStore.saveSummary(runDir, msg);
            resultStore.saveMetadata(runDir, metadata(originalFilename, prompt, 0, 0, 0, 0, started));
            return new VideoDescription(msg, List.of(), runDir, metadata(originalFilename, prompt, 0, 0, 0, 0, started));
        }
        reporter.report("Hittade " + frames.size() + " bildrutor.");

        List<String> partials = new ArrayList<>();
        List<VideoSegment> segments = new ArrayList<>();
        int failed = 0;

        if (frames.size() <= chunkSize) {
            reporter.report("Analyserar bildrutor...");
            List<String> images = frames.stream().map(FrameExtractorService.AnalyzedFrame::base64Image).toList();
            String singlePrompt = prompt + ocrSection(frames);
            String analysis;
            try {
                analysis = reporter.run("Analyserar bildrutor (modellen arbetar)...",
                        () -> videoOllamaClient.analyzeFrames(images, singlePrompt,
                                msg -> reporter.report("Analyserar bildrutor: " + msg)));
            } catch (Exception e) {
                analysis = "Ollama-fel: " + e.getMessage();
            }

            if (isFailure(analysis)) {
                resultStore.saveSummary(runDir, analysis);
                resultStore.saveMetadata(runDir, metadata(originalFilename, prompt, frames.size(), 1, 1, 0, started));
                throw new VideoAnalysisException(analysis);
            }
            resultStore.savePartial(runDir, 1, 1, analysis);
            resultStore.saveSummary(runDir, analysis);
            resultStore.saveMetadata(runDir, metadata(originalFilename, prompt, frames.size(), 1, 0, 1, started));
            reportSaved(reporter, runDir);

            int startSec = frames.get(0).second();
            int endSec = frames.get(frames.size() - 1).second();
            if (!isNoChange(analysis)) {
                segments.add(new VideoSegment(startSec, endSec, analysis.strip()));
            }
            partials.add(analysis);
            return new VideoDescription(analysis, segments, runDir,
                    metadata(originalFilename, prompt, frames.size(), 1, 0, 1, started));
        }

        int totalChunks = (int) Math.ceil((double) frames.size() / chunkSize);

        for (int i = 0; i < frames.size(); i += chunkSize) {
            List<FrameExtractorService.AnalyzedFrame> chunkFrames =
                    frames.subList(i, Math.min(i + chunkSize, frames.size()));
            List<String> chunk = chunkFrames.stream().map(FrameExtractorService.AnalyzedFrame::base64Image).toList();
            int chunkNo = (i / chunkSize) + 1;
            reporter.report("Analyserar del " + chunkNo + " av " + totalChunks + "...");

            int startSec = chunkFrames.get(0).second();
            int endSec = chunkFrames.get(chunkFrames.size() - 1).second();
            String timeRange = formatTime(startSec) + "-" + formatTime(endSec);

            String chunkPrompt = String.format(
                    "You are a technical writer. Analyze the part of this screen recording from %s to %s. "
                            + "The images are in chronological order; each image has a timestamp in the OCR section below.\n\n"
                            + "%s"
                            + "Document ONLY concrete action steps occurring during this time. Use this strict format:\n"
                            + "- [CLICK] If the user clicks a specific button, menu, or icon (name the element).\n"
                            + "- [INPUT] If the user types text, commands, or values (quote exactly what is typed).\n"
                            + "- [STATUS] If an important system message, error, or loading screen appears.\n\n"
                            + "Be extremely concise. If no new interface opens or no action occurs, reply with: 'No change'.",
                    formatTime(startSec), formatTime(endSec), ocrSection(chunkFrames));
            String label = "Analyserar del " + chunkNo + " av " + totalChunks + " (modellen arbetar)...";

            long chunkStart = System.currentTimeMillis();
            String partial;
            try {
                partial = reporter.run(label, () -> videoOllamaClient.analyzeFrames(chunk, chunkPrompt,
                        msg -> reporter.report("Del " + chunkNo + " av " + totalChunks + ": " + msg)));
            } catch (Exception e) {
                log.warn("Del {} av {} kastade undantag: {}", chunkNo, totalChunks, e.getMessage());
                partial = "Ollama-fel: " + e.getMessage();
            }
            double seconds = (System.currentTimeMillis() - chunkStart) / 1000.0;

            String rawPartial = partial;
            if (isFailure(partial)) {
                failed++;
                log.warn("Del {} av {} misslyckades efter {} s: {}", chunkNo, totalChunks,
                        String.format("%.1f", seconds), partial);
                partial = "[" + timeRange + "] (Del " + chunkNo + " kunde inte analyseras: " + partial + ")";
            } else {
                partial = "[" + timeRange + "]\n" + partial.strip();
                resultStore.savePartial(runDir, chunkNo, totalChunks, partial);
                reporter.report(String.format("Del %d av %d klar (%.0f s).", chunkNo, totalChunks, seconds));
                if (!isNoChange(rawPartial)) {
                    segments.add(new VideoSegment(startSec, endSec, rawPartial.strip()));
                }
            }
            partials.add(partial);
        }

        if (failed == totalChunks) {
            String error = "Alla delanalyser misslyckades: " + partials.get(0);
            resultStore.saveMetadata(runDir, metadata(originalFilename, prompt, frames.size(),
                    totalChunks, failed, 0, started));
            throw new VideoAnalysisException(error);
        }

        String summary = summarizeHierarchically(partials, prompt, reporter);

        resultStore.saveSummary(runDir, summary);
        resultStore.saveMetadata(runDir, metadata(originalFilename, prompt, frames.size(),
                totalChunks, failed, totalChunks - failed, started));
        reportSaved(reporter, runDir);
        return new VideoDescription(summary, segments, runDir,
                metadata(originalFilename, prompt, frames.size(), totalChunks, failed, totalChunks - failed, started));
    }

    private String summarizeHierarchically(List<String> partials, String prompt, ProgressReporter reporter) {
        List<String> current = new ArrayList<>();
        for (String p : partials) {
            if (!isNoChange(p)) {
                current.add(p);
            }
        }
        log.info("{} av {} delar innehöll händelser.", current.size(), partials.size());
        if (current.isEmpty()) {
            return "Inga tydliga händelser eller handlingar upptäcktes i inspelningen.";
        }

        int maxChars = videoOllamaClient.getMaxSummaryChars();
        int level = 1;
        while (current.size() > 1 && totalChars(current) > maxChars) {
            List<String> next = new ArrayList<>();
            int groups = (int) Math.ceil((double) current.size() / SUMMARY_GROUP_SIZE);
            for (int i = 0; i < current.size(); i += SUMMARY_GROUP_SIZE) {
                List<String> group = current.subList(i, Math.min(i + SUMMARY_GROUP_SIZE, current.size()));
                int groupNo = (i / SUMMARY_GROUP_SIZE) + 1;
                String msg = "Delsammanfattning nivå " + level + ": grupp " + groupNo + " av " + groups + "...";
                reporter.report(msg);
                String s = reporter.run(msg + " (modellen arbetar)",
                        () -> videoOllamaClient.summarizeAnalyses(group, prompt,
                                m -> reporter.report("Sammanfattning: " + m)));
                next.add(isFailure(s) ? String.join("\n\n", group) : s.strip());
            }
            if (totalChars(next) >= totalChars(current)) {
                log.warn("Hierarkisk sammanfattning krympte inte materialet, avbryter nivåerna.");
                current = next;
                break;
            }
            current = next;
            level++;
        }

        reporter.report("Sammanfattar delanalyser...");
        List<String> finalInput = current;
        return reporter.run("Sammanfattar delanalyser (modellen arbetar)...",
                () -> videoOllamaClient.summarizeAnalyses(finalInput, prompt,
                        m -> reporter.report("Sammanfattning: " + m)));
    }

    private static int totalChars(List<String> texts) {
        int sum = 0;
        for (String t : texts) {
            sum += t == null ? 0 : t.length();
        }
        return sum;
    }

    private static boolean isNoChange(String partial) {
        if (partial == null) {
            return true;
        }
        String body = partial.replaceFirst("^\\[[^\\]]*\\]\\s*", "").strip().toLowerCase();
        return body.isEmpty() || (body.length() < 40 && body.startsWith("no change"));
    }

    private static String ocrSection(List<FrameExtractorService.AnalyzedFrame> frames) {
        StringBuilder sb = new StringBuilder();
        for (FrameExtractorService.AnalyzedFrame f : frames) {
            String t = f.extractedText();
            if (t != null && !t.isBlank()) {
                sb.append("At ").append(formatTime(f.second())).append(":\n").append(t).append("\n");
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        return "To help you read small texts exactly, here is raw OCR text extracted from the frames "
                + "(it may contain errors; trust the images when they disagree):\n\"\"\"\n"
                + sb + "\"\"\"\n\n";
    }

    private static String formatTime(int totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void reportSaved(ProgressReporter reporter, Path runDir) {
        if (runDir != null) {
            reporter.report("Delanalyser och resultat sparade i " + runDir);
        }
    }

    private Map<String, Object> metadata(String filename, String prompt, int frames, int totalChunks,
                                         int failedChunks, int completedChunks, long started) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("filnamn", filename);
        meta.put("prompt", prompt);
        meta.put("modell", videoOllamaClient.getModel());
        meta.put("bildrutor", frames);
        meta.put("delar", totalChunks);
        meta.put("misslyckadeDelar", failedChunks);
        meta.put("klaraDelar", completedChunks);
        meta.put("sekunder", Math.round((System.currentTimeMillis() - started) / 100.0) / 10.0);
        return meta;
    }

    private boolean isFailure(String text) {
        return text == null || text.isBlank()
                || text.startsWith("Ollama-fel:")
                || text.startsWith("Kunde inte tolka svaret");
    }

    private static final class ProgressReporter {
        private final Consumer<String> sink;
        private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

        private ProgressReporter(Consumer<String> sink) {
            this.sink = sink;
        }

        void report(String message) {
            lastActivity.set(System.currentTimeMillis());
            log.info("Videoanalys: {}", message);
            try {
                sink.accept(message);
            } catch (Exception e) {
                log.debug("Kunde inte skicka progress: {}", e.getMessage());
            }
        }

        String run(String heartbeatMessage, Supplier<String> action) {
            Thread heartbeat = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(2000);
                        if (System.currentTimeMillis() - lastActivity.get() >= 10_000) {
                            report(heartbeatMessage);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            });
            heartbeat.setDaemon(true);
            heartbeat.start();
            try {
                return action.get();
            } finally {
                heartbeat.interrupt();
            }
        }
    }
}
