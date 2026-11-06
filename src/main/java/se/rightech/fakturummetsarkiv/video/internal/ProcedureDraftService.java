package se.rightech.fakturummetsarkiv.video.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.video.ProcedureDraft;
import se.rightech.fakturummetsarkiv.video.ProcedureStep;
import se.rightech.fakturummetsarkiv.video.StepKind;
import se.rightech.fakturummetsarkiv.video.VideoAnalysis;
import se.rightech.fakturummetsarkiv.video.VideoAnalysisCompat;
import se.rightech.fakturummetsarkiv.video.VideoAnalysisException;
import se.rightech.fakturummetsarkiv.video.VideoDescriber;
import se.rightech.fakturummetsarkiv.video.VideoOptions;

import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class ProcedureDraftService implements VideoDescriber {

    private static final Logger log = LoggerFactory.getLogger(ProcedureDraftService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${video.chunk-size:6}")
    private int chunkSize;

    private final FrameExtractorService frameExtractorService;
    private final VideoOllamaClient videoOllamaClient;
    private final AnalysisResultStore resultStore;

    public ProcedureDraftService(FrameExtractorService frameExtractorService,
                                VideoOllamaClient videoOllamaClient,
                                AnalysisResultStore resultStore) {
        this.frameExtractorService = frameExtractorService;
        this.videoOllamaClient = videoOllamaClient;
        this.resultStore = resultStore;
    }

    @Override
    public ProcedureDraft draft(Path videoFile, String originalFilename, VideoOptions options,
                                Consumer<String> progress) {
        String filename = (originalFilename == null || originalFilename.isBlank())
                ? (videoFile == null ? "video" : videoFile.getFileName().toString())
                : originalFilename;
        String hint = (options == null) ? null : options.hint();
        Consumer<String> sink = (progress == null) ? msg -> { } : progress;

        Path runDir = resultStore.startRun(filename);
        Path framesDir = (runDir == null) ? null : runDir.resolve("frames");

        long started = System.currentTimeMillis();
        List<FrameExtractorService.AnalyzedFrame> frames;
        try {
            frames = frameExtractorService.extractFrames(videoFile == null ? null : videoFile.toFile(), framesDir);
        } catch (Exception e) {
            throw new VideoAnalysisException("Kunde inte extrahera bildrutor: " + e.getMessage(), e);
        }

        if (frames == null || frames.isEmpty()) {
            String msg = "Kunde inte hitta några läsbara bildrutor i videofilen.";
            if (runDir != null) resultStore.saveSummary(runDir, msg);
            Map<String, Object> meta = baseMetadata(filename, 0, 0, 0, started);
            return new ProcedureDraft("Procedurutkast", "", List.of(), List.of(), List.of(msg), filename,
                    videoOllamaClient.getModel(), runDir, meta);
        }
        sink.accept("Hittade " + frames.size() + " bildrutor.");

        // Per-chunk structured extraction
        List<Map<String, Object>> chunkSteps = new ArrayList<>();
        int failed = 0;
        int totalChunks;
        if (frames.size() <= chunkSize) {
            totalChunks = 1;
            if (Thread.currentThread().isInterrupted()) {
                videoOllamaClient.unload();
                throw new VideoAnalysisException("Avbruten av användaren.");
            }
            String analysis = analyzeChunk(frames, 1, 1, hint, sink);
            if (isFailure(analysis)) {
                failed = 1;
            } else {
                addChunkSteps(chunkSteps, analysis, frames);
            }
            if (runDir != null) {
                resultStore.savePartial(runDir, 1, 1, analysis);
                resultStore.saveSummary(runDir, analysis);
            }
        } else {
            totalChunks = (int) Math.ceil((double) frames.size() / chunkSize);
            int chunkNo = 0;
            for (int i = 0; i < frames.size(); i += chunkSize) {
                if (Thread.currentThread().isInterrupted()) {
                    videoOllamaClient.unload();
                    throw new VideoAnalysisException("Avbruten av användaren.");
                }
                List<FrameExtractorService.AnalyzedFrame> chunkFrames =
                        frames.subList(i, Math.min(i + chunkSize, frames.size()));
                chunkNo++;
                final int cn = chunkNo;
                sink.accept("Analyserar del " + cn + " av " + totalChunks + "...");
                String analysis = analyzeChunk(chunkFrames, cn, totalChunks, hint,
                        msg -> sink.accept("Del " + cn + " av " + totalChunks + ": " + msg));
                if (isFailure(analysis)) {
                    failed++;
                } else {
                    addChunkSteps(chunkSteps, analysis, chunkFrames);
                }
                if (runDir != null) {
                    resultStore.savePartial(runDir, chunkNo, totalChunks, analysis);
                }
            }
        }

        if (failed == (frames.size() <= chunkSize ? 1 : totalChunks)) {
            videoOllamaClient.unload();
            throw new VideoAnalysisException("Alla delanalyser misslyckades.");
        }

        // Merge + dedupe
        List<ProcedureStep> merged = mergeSteps(chunkSteps, frames);

        // Final pass for title/purpose/prerequisites/warnings + step titles
        String title = "Procedurutkast";
        String purpose = "";
        List<String> prerequisites = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            Map<String, Object> finalResult = finalPass(merged, hint, sink);
            if (finalResult != null) {
                if (finalResult.get("title") instanceof String t && !t.isBlank()) title = t;
                if (finalResult.get("purpose") instanceof String p && !p.isBlank()) purpose = p;
                if (finalResult.get("prerequisites") instanceof List<?> pr) {
                    for (Object o : pr) if (o != null) prerequisites.add(String.valueOf(o));
                }
                if (finalResult.get("warnings") instanceof List<?> w) {
                    for (Object o : w) if (o != null) warnings.add(String.valueOf(o));
                }
                if (finalResult.get("stepTitles") instanceof List<?> st) {
                    for (int i = 0; i < merged.size() && i < st.size(); i++) {
                        Object v = st.get(i);
                        if (v != null && !String.valueOf(v).isBlank()) {
                            ProcedureStep old = merged.get(i);
                            merged.set(i, new ProcedureStep(old.number(), String.valueOf(v), old.instruction(),
                                    old.kind(), old.target(), old.inputText(), old.expectedResult(),
                                    old.startSecond(), old.endSecond(), old.frameImage(), old.uncertain()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Slutpass misslyckades: {}", e.getMessage());
            warnings.add("Slutpass (titel/syfte) misslyckades: " + e.getMessage());
        }

        // Renumber
        List<ProcedureStep> numbered = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            ProcedureStep s = merged.get(i);
            numbered.add(new ProcedureStep(i + 1, s.title(), s.instruction(), s.kind(), s.target(),
                    s.inputText(), s.expectedResult(), s.startSecond(), s.endSecond(), s.frameImage(), s.uncertain()));
        }

        if (runDir != null) {
            resultStore.saveSummary(runDir, title + "\n\n" + purpose);
            Map<String, Object> meta = baseMetadata(filename, frames.size(), totalChunks,
                    (frames.size() <= chunkSize ? 1 : totalChunks) - failed, started);
            meta.put("steg", numbered.size());
            resultStore.saveMetadata(runDir, meta);
            // Save procedure-draft.json
            saveDraftJson(runDir, new ProcedureDraft(title, purpose, prerequisites, numbered, warnings,
                    filename, videoOllamaClient.getModel(), runDir, meta));
            saveDraftMarkdown(runDir, title, purpose, prerequisites, numbered, warnings);
        }

        Map<String, Object> meta = baseMetadata(filename, frames.size(),
                frames.size() <= chunkSize ? 1 : totalChunks, 0, started);
        meta.put("steg", numbered.size());
        videoOllamaClient.unload();
        return new ProcedureDraft(title, purpose, prerequisites, numbered, warnings, filename,
                videoOllamaClient.getModel(), runDir, meta);
    }

    private String analyzeChunk(List<FrameExtractorService.AnalyzedFrame> chunkFrames, int chunkNo, int total,
                                String hint, Consumer<String> progress) {
        List<String> images = chunkFrames.stream().map(FrameExtractorService.AnalyzedFrame::base64Image).toList();
        int startSec = chunkFrames.get(0).second();
        int endSec = chunkFrames.get(chunkFrames.size() - 1).second();
        String ocr = ocrSection(chunkFrames);
        String prompt = buildChunkPrompt(startSec, endSec, hint, ocr);

        String format = "json";
        try {
            return progress == null
                    ? videoOllamaClient.analyzeFrames(images, prompt, format, null)
                    : videoOllamaClient.analyzeFrames(images, prompt, format, progress);
        } catch (Exception e) {
            return "Ollama-fel: " + e.getMessage();
        }
    }

    private String buildChunkPrompt(int startSec, int endSec, String hint, String ocr) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a technical writer. Analyze the part of this screen recording from ")
                .append(formatTime(startSec)).append(" to ").append(formatTime(endSec)).append(".\n");
        if (hint != null && !hint.isBlank()) {
            sb.append("Context from the user: ").append(hint).append("\n");
        }
        sb.append("The images are in chronological order. Each image has a timestamp in the OCR section below.\n\n");
        if (!ocr.isBlank()) sb.append(ocr).append("\n");
        sb.append("Extract ONLY concrete action steps occurring during this time. Return a JSON object:\n");
        sb.append("{ \"steps\": [ { \"title\": string, \"instruction\": string, \"kind\": \"CLICK|INPUT|KEY|NAVIGATE|WAIT|VERIFY|OTHER\", ")
                .append("\"target\": string, \"inputText\": string or null, \"expectedResult\": string, ")
                .append("\"frameSecond\": integer (second that best shows the step), \"uncertain\": boolean } ] }\n");
        sb.append("Rules:\n");
        sb.append("- instruction: one clear imperative action.\n");
        sb.append("- inputText: quote EXACTLY what is typed (from OCR when possible); null if nothing typed.\n");
        sb.append("- frameSecond: the second of the image that best shows the result of the step.\n");
        sb.append("- uncertain: true if OCR/model was unsure.\n");
        sb.append("- If no action occurs, return { \"steps\": [] }.\n");
        sb.append("Write all text fields in English. Return only JSON, no markdown.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void addChunkSteps(List<Map<String, Object>> chunkSteps, String analysis,
                               List<FrameExtractorService.AnalyzedFrame> chunkFrames) {
        try {
            JsonNode node = JSON.readTree(stripMarkdownFence(analysis));
            JsonNode arr = node.path("steps");
            if (!arr.isArray()) return;
            for (JsonNode s : arr) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("title", textOrNull(s, "title"));
                step.put("instruction", textOrNull(s, "instruction"));
                step.put("kind", textOrNull(s, "kind"));
                step.put("target", textOrNull(s, "target"));
                step.put("inputText", s.has("inputText") && !s.get("inputText").isNull() ? s.get("inputText").asText() : null);
                step.put("expectedResult", textOrNull(s, "expectedResult"));
                int fs = s.path("frameSecond").asInt(chunkFrames.get(0).second());
                step.put("frameSecond", fs);
                step.put("uncertain", s.path("uncertain").asBoolean(false));
                chunkSteps.add(step);
            }
        } catch (Exception e) {
            log.warn("Kunde inte tolka JSON från modellen: {}", e.getMessage());
        }
    }

    private String textOrNull(JsonNode s, String field) {
        JsonNode n = s.path(field);
        return n.isMissingNode() || n.isNull() ? "" : n.asText();
    }

    private String stripMarkdownFence(String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
        }
        return t.strip();
    }

    private List<ProcedureStep> mergeSteps(List<Map<String, Object>> chunkSteps,
                                           List<FrameExtractorService.AnalyzedFrame> frames) {
        List<ProcedureStep> steps = new ArrayList<>();
        for (Map<String, Object> cs : chunkSteps) {
            int fs = (cs.get("frameSecond") instanceof Number n) ? n.intValue() : frames.get(0).second();
            Path frameImage = nearestFrame(frames, fs);
            String kindStr = String.valueOf(cs.getOrDefault("kind", "OTHER"));
            StepKind kind;
            try { kind = StepKind.valueOf(kindStr.toUpperCase()); } catch (Exception e) { kind = StepKind.OTHER; }
            steps.add(new ProcedureStep(0,
                    String.valueOf(cs.getOrDefault("title", "")),
                    String.valueOf(cs.getOrDefault("instruction", "")),
                    kind,
                    String.valueOf(cs.getOrDefault("target", "")),
                    cs.get("inputText") == null ? null : String.valueOf(cs.get("inputText")),
                    String.valueOf(cs.getOrDefault("expectedResult", "")),
                    fs, fs, frameImage,
                    Boolean.TRUE.equals(cs.get("uncertain"))));
        }
        // sort by frameSecond
        steps.sort(Comparator.comparingInt(ProcedureStep::startSecond));
        // dedupe adjacent identical (title+inputText)
        List<ProcedureStep> deduped = new ArrayList<>();
        for (ProcedureStep s : steps) {
            if (!deduped.isEmpty()) {
                ProcedureStep last = deduped.get(deduped.size() - 1);
                if (last.title().equals(s.title()) && java.util.Objects.equals(last.inputText(), s.inputText())) {
                    continue;
                }
            }
            deduped.add(s);
        }
        return deduped;
    }

    private Path nearestFrame(List<FrameExtractorService.AnalyzedFrame> frames, int second) {
        if (frames == null || frames.isEmpty()) return null;
        FrameExtractorService.AnalyzedFrame best = frames.get(0);
        int bestDiff = Math.abs(best.second() - second);
        for (FrameExtractorService.AnalyzedFrame f : frames) {
            int d = Math.abs(f.second() - second);
            if (d < bestDiff) { bestDiff = d; best = f; }
        }
        return best.framePath();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> finalPass(List<ProcedureStep> steps, String hint, Consumer<String> sink) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here are the extracted steps of a screen recording procedure (JSON):\n");
        for (ProcedureStep s : steps) {
            sb.append("- ").append(s.title()).append(": ").append(s.instruction());
            if (s.inputText() != null && !s.inputText().isBlank()) sb.append(" [input: ").append(s.inputText()).append("]");
            sb.append(" (").append(formatTime(s.startSecond())).append(")\n");
        }
        if (hint != null && !hint.isBlank()) sb.append("\nContext: ").append(hint).append("\n");
        sb.append("\nWrite all text fields in English. Produce a JSON object with: \"title\" (short procedure title), \"purpose\" (one sentence), ")
                .append("\"prerequisites\" (array of strings), \"warnings\" (array of strings, include uncertain steps), ")
                .append("\"stepTitles\" (array of improved short titles, same length and order as steps; ")
                .append("do NOT change any inputText). Return only JSON.");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are a technical writer. Respond with JSON only."));
        messages.add(Map.of("role", "user", "content", sb.toString()));

        String raw = videoOllamaClient.summarizeAnalyses(List.of(sb.toString()), hint == null ? "" : hint, null);
        // summarizeAnalyses wraps text; better: direct chat not exposed. Use it anyway.
        try {
            return JSON.readValue(stripMarkdownFence(raw), Map.class);
        } catch (Exception e) {
            log.warn("Kunde inte tolka slutpass-JSON: {}", e.getMessage());
            return null;
        }
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
        return "OCR text extracted from the frames (may contain errors; trust the images when they disagree):\n"
                + sb.toString() + "\n";
    }

    private boolean isFailure(String text) {
        return text == null || text.isBlank()
                || text.startsWith("Ollama-fel:")
                || text.startsWith("Kunde inte tolka svaret");
    }

    private static String formatTime(int totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private Map<String, Object> baseMetadata(String filename, int frames, int totalChunks, int completedChunks, long started) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("filnamn", filename);
        meta.put("modell", videoOllamaClient.getModel());
        meta.put("bildrutor", frames);
        meta.put("delar", totalChunks);
        meta.put("klaraDelar", completedChunks);
        meta.put("sekunder", Math.round((System.currentTimeMillis() - started) / 100.0) / 10.0);
        return meta;
    }

    private void saveDraftJson(Path runDir, ProcedureDraft draft) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", draft.title());
            data.put("purpose", draft.purpose());
            data.put("prerequisites", draft.prerequisites());
            data.put("warnings", draft.warnings());
            data.put("sourceFilename", draft.sourceFilename());
            data.put("model", draft.model());
            List<Map<String, Object>> steps = new ArrayList<>();
            for (ProcedureStep s : draft.steps()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("number", s.number());
                m.put("title", s.title());
                m.put("instruction", s.instruction());
                m.put("kind", s.kind().name());
                m.put("target", s.target());
                m.put("inputText", s.inputText());
                m.put("expectedResult", s.expectedResult());
                m.put("startSecond", s.startSecond());
                m.put("endSecond", s.endSecond());
                m.put("frameImage", s.frameImage() == null ? null : s.frameImage().toString());
                m.put("uncertain", s.uncertain());
                steps.add(m);
            }
            data.put("steps", steps);
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            java.nio.file.Files.writeString(runDir.resolve("procedure-draft.json"), json);
        } catch (Exception e) {
            log.warn("Kunde inte spara procedure-draft.json: {}", e.getMessage());
        }
    }

    private void saveDraftMarkdown(Path runDir, String title, String purpose, List<String> prerequisites,
                                  List<ProcedureStep> steps, List<String> warnings) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(title).append("\n\n");
            if (purpose != null && !purpose.isBlank()) sb.append(purpose).append("\n\n");
            if (!prerequisites.isEmpty()) {
                sb.append("## Prerequisites\n");
                for (String p : prerequisites) sb.append("- ").append(p).append("\n");
                sb.append("\n");
            }
            if (!warnings.isEmpty()) {
                sb.append("## Warnings\n");
                for (String w : warnings) sb.append("- ").append(w).append("\n");
                sb.append("\n");
            }
            sb.append("## Steps\n");
            for (ProcedureStep s : steps) {
                sb.append(s.number()).append(". **").append(s.title()).append("** (")
                        .append(formatTime(s.startSecond())).append(")\n");
                sb.append("   ").append(s.instruction()).append("\n");
                if (s.inputText() != null && !s.inputText().isBlank()) {
                    sb.append("   Input: `").append(s.inputText()).append("`\n");
                }
                if (s.expectedResult() != null && !s.expectedResult().isBlank()) {
                    sb.append("   Expected: ").append(s.expectedResult()).append("\n");
                }
                if (s.uncertain()) sb.append("   ⚠ Uncertain step\n");
                if (s.frameImage() != null) sb.append("   Image: ").append(s.frameImage().getFileName()).append("\n");
            }
            java.nio.file.Files.writeString(runDir.resolve("procedure-draft.md"), sb.toString());
        } catch (Exception e) {
            log.warn("Kunde inte spara procedure-draft.md: {}", e.getMessage());
        }
    }

    @Override
    public VideoAnalysis describe(Path videoFile, String originalFilename, VideoOptions options,
                                  Consumer<String> progress) {
        // För tillfället: använd draft() och konvertera till VideoAnalysis
        // I Fas V2 kommer detta att implementeras med nya prompts
        ProcedureDraft draft = draft(videoFile, originalFilename, options, progress);
        return VideoAnalysisCompat.fromDraft(draft);
    }

}
