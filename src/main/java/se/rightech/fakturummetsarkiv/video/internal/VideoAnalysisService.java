package se.rightech.fakturummetsarkiv.video.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.video.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoAnalysisService implements VideoDescriber {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FrameExtractorService frameExtractorService;
    private final VideoOllamaClient videoOllamaClient;
    private final AnalysisResultStore resultStore;

    public VideoAnalysisService(FrameExtractorService frameExtractorService,
                                VideoOllamaClient videoOllamaClient,
                                AnalysisResultStore resultStore) {
        this.frameExtractorService = frameExtractorService;
        this.videoOllamaClient = videoOllamaClient;
        this.resultStore = resultStore;
    }

    @Override
    public VideoAnalysis describe(Path videoFile, String originalFilename, VideoOptions options,
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
            return new VideoAnalysis(
                    "Videoanalys",
                    msg,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(msg),
                    filename,
                    videoOllamaClient.getModel(),
                    runDir,
                    meta
            );
        }
        sink.accept("Hittade " + frames.size() + " bildrutor.");

        // Analysera chunks med neutrala beskrivningar
        List<VideoEvent> allEvents = new ArrayList<>();
        List<String> allUncertainties = new ArrayList<>();
        int failed = 0;
        int totalChunks;
        
        int chunkSize = 6; // Default chunk size
        if (frames.size() <= chunkSize) {
            totalChunks = 1;
            try {
                String analysis = analyzeDescriptionChunk(frames, 1, 1, hint, sink);
                List<VideoEvent> chunkEvents = parseChunkEvents(analysis, frames);
                allEvents.addAll(chunkEvents);
                
                if (runDir != null) {
                    resultStore.savePartial(runDir, 1, 1, analysis);
                    resultStore.saveSummary(runDir, analysis);
                }
            } catch (Exception e) {
                failed = 1;
                allUncertainties.add("Analysis failed for chunk 1-1: " + e.getMessage());
                log.warn("Chunk-analys misslyckades: {}", e.getMessage());
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
                try {
                    String analysis = analyzeDescriptionChunk(chunkFrames, cn, totalChunks, hint,
                            msg -> sink.accept("Del " + cn + " av " + totalChunks + ": " + msg));
                    List<VideoEvent> chunkEvents = parseChunkEvents(analysis, chunkFrames);
                    allEvents.addAll(chunkEvents);
                    
                    if (runDir != null) {
                        resultStore.savePartial(runDir, chunkNo, totalChunks, analysis);
                    }
                } catch (Exception e) {
                    failed++;
                    allUncertainties.add("Analysis failed for chunk " + cn + "-" + totalChunks + ": " + e.getMessage());
                    log.warn("Chunk-analys misslyckades för del {}: {}", cn, e.getMessage());
                }
            }
        }

        if (failed == totalChunks) {
            videoOllamaClient.unload();
            throw new VideoAnalysisException("Alla delanalyser misslyckades.");
        }

        // Final summary
        String title = "Videoanalys";
        String summary = "";
        List<String> applications = new ArrayList<>();
        List<String> notable = new ArrayList<>();
        
        try {
            Map<String, Object> finalResult = finalSummaryPass(allEvents, hint, sink);
            if (finalResult != null) {
                if (finalResult.get("title") instanceof String t && !t.isBlank()) title = t;
                if (finalResult.get("summary") instanceof String s && !s.isBlank()) summary = s;
                if (finalResult.get("applications") instanceof List<?> apps) {
                    for (Object o : apps) if (o != null) applications.add(String.valueOf(o));
                }
                if (finalResult.get("notable") instanceof List<?> n) {
                    for (Object o : n) if (o != null) notable.add(String.valueOf(o));
                }
                if (finalResult.get("uncertainties") instanceof List<?> u) {
                    for (Object o : u) if (o != null) allUncertainties.add(String.valueOf(o));
                }
            }
        } catch (Exception e) {
            log.warn("Slutsammanfattning misslyckades: {}", e.getMessage());
            allUncertainties.add("Final summary could not be generated: " + e.getMessage());
        }

        Map<String, Object> meta = baseMetadata(filename, frames.size(), 
                frames.isEmpty() ? 0 : frames.get(frames.size() - 1).second(),
                allEvents.size(), started);

        VideoAnalysis analysis = new VideoAnalysis(
                title,
                summary,
                applications,
                allEvents,
                notable,
                allUncertainties,
                filename,
                videoOllamaClient.getModel(),
                runDir,
                meta
        );

        // Spara analysis.json och analysis.md
        if (runDir != null) {
            saveAnalysisFiles(runDir, analysis);
        }

        sink.accept("Analysen är klar.");
        return analysis;
    }

    @Override
    @Deprecated
    public ProcedureDraft draft(Path videoFile, String originalFilename, VideoOptions options,
                                Consumer<String> progress) {
        // För bakåtkompatibilitet: kör describe() och konvertera
        VideoAnalysis analysis = describe(videoFile, originalFilename, options, progress);
        return VideoAnalysisCompat.toDraft(analysis);
    }

    private String analyzeDescriptionChunk(List<FrameExtractorService.AnalyzedFrame> chunkFrames,
                                           int chunkNo, int totalChunks, String hint,
                                           Consumer<String> sink) {
        int startSec = chunkFrames.get(0).second();
        int endSec = chunkFrames.get(chunkFrames.size() - 1).second();
        
        sink.accept("Analyserar bildrutor från " + formatTime(startSec) + " till " + formatTime(endSec) + "...");
        
        String ocr = buildOcrText(chunkFrames);
        String prompt = buildDescriptionChunkPrompt(startSec, endSec, hint, ocr);
        
        List<String> images = chunkFrames.stream()
                .map(FrameExtractorService.AnalyzedFrame::base64Image)
                .toList();
        
        return videoOllamaClient.analyzeFrames(images, prompt, null, null);
    }

    private List<VideoEvent> parseChunkEvents(String analysis, List<FrameExtractorService.AnalyzedFrame> chunkFrames) {
        List<VideoEvent> events = new ArrayList<>();
        try {
            JsonNode node = JSON.readTree(stripMarkdownFence(analysis));
            JsonNode arr = node.path("events");
            if (!arr.isArray()) return events;
            
            for (JsonNode e : arr) {
                int start = e.path("start").asInt(chunkFrames.get(0).second());
                int end = e.path("end").asInt(chunkFrames.get(chunkFrames.size() - 1).second());
                String description = textOrNull(e, "description");
                String action = textOrNull(e, "action");
                String typedText = e.has("typedText") && !e.get("typedText").isNull() ? e.get("typedText").asText() : null;
                List<String> onScreenText = new ArrayList<>();
                JsonNode ost = e.path("onScreenText");
                if (ost.isArray()) {
                    for (JsonNode item : ost) {
                        onScreenText.add(item.asText());
                    }
                }
                String application = textOrNull(e, "application");
                int frameSecond = e.path("frameSecond").asInt(chunkFrames.get(0).second());
                Path frameImage = findFrameImage(chunkFrames, frameSecond);
                boolean uncertain = e.path("uncertain").asBoolean(false);
                
                events.add(new VideoEvent(start, end, description, action, typedText, onScreenText,
                        application, frameSecond, frameImage, uncertain));
            }
        } catch (Exception e) {
            log.warn("Kunde inte tolka JSON från modellen: {}", e.getMessage());
        }
        return events;
    }

    private Map<String, Object> finalSummaryPass(List<VideoEvent> events, String hint, Consumer<String> sink) {
        sink.accept("Skapar sammanfattning...");
        
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Create a summary of the video based on these events:\n\n");
        
        for (VideoEvent event : events) {
            userMsg.append("[").append(formatTime(event.startSecond())).append("-").append(formatTime(event.endSecond())).append("] ")
                   .append(event.description());
            if (event.action() != null) {
                userMsg.append(" (").append(event.action()).append(")");
            }
            if (event.typedText() != null) {
                userMsg.append(" typed: \"").append(event.typedText()).append("\"");
            }
            if (!event.onScreenText().isEmpty()) {
                userMsg.append(" onScreen: ").append(event.onScreenText());
            }
            if (event.application() != null) {
                userMsg.append(" app: ").append(event.application());
            }
            if (event.uncertain()) {
                userMsg.append(" [UNCERTAIN]");
            }
            userMsg.append("\n");
        }
        
        if (hint != null && !hint.isBlank()) {
            userMsg.append("\nContext from user: ").append(hint).append("\n");
        }
        
        String systemPrompt = """
            You are summarizing a video analysis. Return JSON with:
            - title: short descriptive title
            - summary: 2-3 sentences describing the overall video
            - applications: list of application names seen in the video
            - notable: list of notable observations (errors, warnings, important UI elements)
            - uncertainties: list of things that were unclear or uncertain
            
            Return only JSON, no markdown.
            """;
        
        String response = videoOllamaClient.analyzeFrames(List.of(), systemPrompt + "\n\n" + userMsg, null, null);
        
        try {
            JsonNode node = JSON.readTree(stripMarkdownFence(response));
            return JSON.convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Kunde inte tolka summary-JSON: {}", e.getMessage());
            return null;
        }
    }

    String buildDescriptionChunkPrompt(int startSec, int endSec, String hint, String ocr) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are describing a screen recording neutrally. Describe what happens from ")
                .append(formatTime(startSec)).append(" to ").append(formatTime(endSec)).append(".\n");
        if (hint != null && !hint.isBlank()) {
            sb.append("Context from the user: ").append(hint).append("\n");
        }
        sb.append("The images are in chronological order. Each image has a timestamp in the OCR section below.\n\n");
        if (!ocr.isBlank()) sb.append(ocr).append("\n");
        
        sb.append("Describe the events occurring during this time. Return a JSON object:\n");
        sb.append("{ \"events\": [ { ");
        sb.append("\"start\": integer (start second), ");
        sb.append("\"end\": integer (end second), ");
        sb.append("\"description\": string (neutral description of what happens), ");
        sb.append("\"action\": string or null (what the user does, e.g. \"click\", \"input\", \"navigate\"), ");
        sb.append("\"typedText\": string or null (text typed EXACTLY as shown, preserve case and special characters), ");
        sb.append("\"onScreenText\": [string] (important verbatim texts visible on screen: menu items, buttons, messages, paths, values), ");
        sb.append("\"application\": string or null (application name if visible), ");
        sb.append("\"frameSecond\": integer (second of the frame that best shows this event), ");
        sb.append("\"uncertain\": boolean } ] }\n");
        
        sb.append("Rules:\n");
        sb.append("- Use neutral, descriptive language (not commanding or instructing). Describe what IS happening, not what SHOULD be done.\n");
        sb.append("- Preserve typedText and onScreenText EXACTLY as shown (from OCR when possible). Never translate or \"improve\" them.\n");
        sb.append("- If something is unclear or uncertain, set uncertain=true and note what is unclear in description.\n");
        sb.append("- If no visible change occurs, create one event with description=\"No visible change on screen\" and action=null.\n");
        sb.append("- Include dialogs, error messages, output text, window changes, waiting periods, not just user actions.\n");
        sb.append("- frameSecond: the second of the image that best shows this event.\n");
        sb.append("Write all text fields in English. Return only JSON, no markdown.");
        return sb.toString();
    }

    private String buildOcrText(List<FrameExtractorService.AnalyzedFrame> frames) {
        StringBuilder sb = new StringBuilder();
        for (FrameExtractorService.AnalyzedFrame frame : frames) {
            if (frame.extractedText() != null && !frame.extractedText().isBlank()) {
                sb.append("[").append(formatTime(frame.second())).append("] ").append(frame.extractedText()).append("\n");
            }
        }
        return sb.toString();
    }

    private Path findFrameImage(List<FrameExtractorService.AnalyzedFrame> frames, int targetSecond) {
        return frames.stream()
                .min((a, b) -> Math.abs(a.second() - targetSecond) - Math.abs(b.second() - targetSecond))
                .map(FrameExtractorService.AnalyzedFrame::framePath)
                .orElse(null);
    }

    private void saveAnalysisFiles(Path runDir, VideoAnalysis analysis) {
        try {
            // Spara analysis.json
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(analysis);
            java.nio.file.Files.writeString(runDir.resolve("analysis.json"), json);
            
            // Spara analysis.md
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(analysis.title()).append("\n\n");
            sb.append(analysis.summary()).append("\n\n");
            if (!analysis.applications().isEmpty()) {
                sb.append("## Applications\n");
                for (String app : analysis.applications()) {
                    sb.append("- ").append(app).append("\n");
                }
                sb.append("\n");
            }
            sb.append("## Timeline\n");
            for (VideoEvent event : analysis.events()) {
                sb.append("**[").append(formatTime(event.startSecond())).append("-").append(formatTime(event.endSecond())).append("]** ");
                sb.append(event.description());
                if (event.action() != null) {
                    sb.append(" (").append(event.action()).append(")");
                }
                if (event.typedText() != null) {
                    sb.append(" | typed: `").append(event.typedText()).append("`");
                }
                if (!event.onScreenText().isEmpty()) {
                    sb.append(" | onScreen: ").append(event.onScreenText());
                }
                if (event.application() != null) {
                    sb.append(" | app: ").append(event.application());
                }
                if (event.uncertain()) {
                    sb.append(" ⚠");
                }
                if (event.frameImage() != null) {
                    sb.append(" | frame: ").append(event.frameImage().getFileName());
                }
                sb.append("\n\n");
            }
            if (!analysis.notable().isEmpty()) {
                sb.append("## Notable\n");
                for (String note : analysis.notable()) {
                    sb.append("- ").append(note).append("\n");
                }
                sb.append("\n");
            }
            if (!analysis.uncertainties().isEmpty()) {
                sb.append("## Uncertainties\n");
                for (String unc : analysis.uncertainties()) {
                    sb.append("- ").append(unc).append("\n");
                }
            }
            java.nio.file.Files.writeString(runDir.resolve("analysis.md"), sb.toString());
        } catch (Exception e) {
            log.warn("Kunde inte spara analysis-filer: {}", e.getMessage());
        }
    }

    private Map<String, Object> baseMetadata(String filename, int frameCount, int lastSecond,
                                             int eventCount, long started) {
        return Map.of(
                "sourceFilename", filename,
                "frameCount", frameCount,
                "durationSeconds", lastSecond,
                "eventCount", eventCount,
                "analyzedAt", java.time.Instant.ofEpochMilli(started).toString()
        );
    }

    private String formatTime(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%d:%02d", m, s);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String t = n.asText();
        return t.isBlank() ? null : t;
    }

    private String stripMarkdownFence(String text) {
        if (text == null) return "";
        String stripped = text.trim();
        if (stripped.startsWith("```json")) {
            stripped = stripped.substring(7);
        } else if (stripped.startsWith("```")) {
            stripped = stripped.substring(3);
        }
        if (stripped.endsWith("```")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        return stripped.trim();
    }
}
