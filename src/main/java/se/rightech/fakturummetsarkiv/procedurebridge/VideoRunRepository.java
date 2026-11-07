package se.rightech.fakturummetsarkiv.procedurebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.video.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class VideoRunRepository {

    private static final Logger log = LoggerFactory.getLogger(VideoRunRepository.class);
    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path outputDir;

    public VideoRunRepository(@Value("${video.output-dir:analyser}") String outputDir) {
        this.outputDir = Paths.get(outputDir).toAbsolutePath().normalize();
    }

    public boolean isValidRunId(String runId) {
        return runId != null && !runId.isBlank() && RUN_ID.matcher(runId).matches();
    }

    private Path resolveRunDir(String runId) {
        if (!isValidRunId(runId)) return null;
        Path candidate = outputDir.resolve(runId).normalize();
        if (!candidate.startsWith(outputDir)) return null;
        return candidate;
    }

    /**
     * Hittar en videoanalys. Försöker först analysis.json (nytt format),
     * faller tillbaka på procedure-draft.json (gammalt format) via VideoAnalysisCompat.
     */
    public VideoAnalysis findAnalysis(String runId) {
        Path runDir = resolveRunDir(runId);
        if (runDir == null || !Files.exists(runDir)) return null;

        // Försök först nytt format
        Path analysisFile = runDir.resolve("analysis.json");
        if (Files.exists(analysisFile)) {
            return loadVideoAnalysis(runDir, analysisFile);
        }

        // Fall tillbaka på gammalt format
        Path draftFile = runDir.resolve("procedure-draft.json");
        if (Files.exists(draftFile)) {
            return loadFromLegacyDraft(runDir, draftFile);
        }

        return null;
    }

    /**
     * @deprecated Använd {@link #findAnalysis(String)} istället.
     * Hittar en ProcedureDraft (gammalt format).
     */
    @Deprecated
    public ProcedureDraft find(String runId) {
        Path runDir = resolveRunDir(runId);
        if (runDir == null || !Files.exists(runDir)) return null;
        Path jsonFile = runDir.resolve("procedure-draft.json");
        if (!Files.exists(jsonFile)) return null;
        try {
            JsonNode node = JSON.readTree(Files.readString(jsonFile));
            List<ProcedureStep> steps = new ArrayList<>();
            JsonNode stepsNode = node.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode s : stepsNode) {
                    String kindStr = s.path("kind").asText("OTHER");
                    StepKind kind;
                    try { kind = StepKind.valueOf(kindStr.toUpperCase()); } catch (Exception e) { kind = StepKind.OTHER; }
                    String frameStr = s.path("frameImage").asText("");
                    Path framePath = null;
                    if (!frameStr.isBlank()) {
                        String fileName = Path.of(frameStr).getFileName().toString();
                        Path candidate = runDir.resolve("frames").resolve(fileName).normalize();
                        if (candidate.startsWith(runDir) && Files.exists(candidate)) framePath = candidate;
                    }
                    steps.add(new ProcedureStep(
                            s.path("number").asInt(0),
                            s.path("title").asText(""),
                            s.path("instruction").asText(""),
                            kind,
                            s.path("target").asText(""),
                            s.has("inputText") && !s.get("inputText").isNull() ? s.get("inputText").asText() : null,
                            s.path("expectedResult").asText(""),
                            s.path("startSecond").asInt(0),
                            s.path("endSecond").asInt(0),
                            framePath,
                            s.path("uncertain").asBoolean(false)));
                }
            }
            List<String> prereqs = new ArrayList<>();
            if (node.path("prerequisites").isArray()) node.path("prerequisites").forEach(n -> prereqs.add(n.asText()));
            List<String> warnings = new ArrayList<>();
            if (node.path("warnings").isArray()) node.path("warnings").forEach(n -> warnings.add(n.asText()));
            return new ProcedureDraft(
                    node.path("title").asText(""),
                    node.path("purpose").asText(""),
                    prereqs,
                    steps,
                    warnings,
                    node.path("sourceFilename").asText(""),
                    node.path("model").asText(""),
                    runDir,
                    Map.of());
        } catch (Exception e) {
            log.warn("Kunde inte läsa körning {}: {}", runId, e.getMessage());
            return null;
        }
    }

    private VideoAnalysis loadVideoAnalysis(Path runDir, Path analysisFile) {
        try {
            JsonNode node = JSON.readTree(Files.readString(analysisFile));

            String title = node.path("title").asText("");
            String summary = node.path("summary").asText("");
            String sourceFilename = node.path("sourceFilename").asText("");
            String model = node.path("model").asText("");

            List<String> applications = new ArrayList<>();
            if (node.path("applications").isArray()) {
                node.path("applications").forEach(n -> applications.add(n.asText()));
            }

            List<String> notable = new ArrayList<>();
            if (node.path("notable").isArray()) {
                node.path("notable").forEach(n -> notable.add(n.asText()));
            }

            List<String> uncertainties = new ArrayList<>();
            if (node.path("uncertainties").isArray()) {
                node.path("uncertainties").forEach(n -> uncertainties.add(n.asText()));
            }

            List<VideoEvent> events = new ArrayList<>();
            JsonNode eventsNode = node.path("events");
            if (eventsNode.isArray()) {
                for (JsonNode e : eventsNode) {
                    String frameStr = e.path("frameImage").asText("");
                    Path framePath = null;
                    if (!frameStr.isBlank()) {
                        String fileName = Path.of(frameStr).getFileName().toString();
                        Path candidate = runDir.resolve("frames").resolve(fileName).normalize();
                        if (candidate.startsWith(runDir) && Files.exists(candidate)) framePath = candidate;
                    }

                    List<String> onScreenText = new ArrayList<>();
                    if (e.path("onScreenText").isArray()) {
                        e.path("onScreenText").forEach(n -> onScreenText.add(n.asText()));
                    }

                    events.add(new VideoEvent(
                            e.path("startSecond").asInt(0),
                            e.path("endSecond").asInt(0),
                            e.path("description").asText(""),
                            e.has("action") && !e.get("action").isNull() ? e.get("action").asText() : null,
                            e.has("typedText") && !e.get("typedText").isNull() ? e.get("typedText").asText() : null,
                            onScreenText,
                            e.has("application") && !e.get("application").isNull() ? e.get("application").asText() : null,
                            e.path("frameSecond").asInt(0),
                            framePath,
                            e.path("uncertain").asBoolean(false)
                    ));
                }
            }

            return new VideoAnalysis(
                    title,
                    summary,
                    applications,
                    events,
                    notable,
                    uncertainties,
                    sourceFilename,
                    model,
                    runDir,
                    Map.of()
            );
        } catch (Exception e) {
            log.warn("Kunde inte läsa videoanalys {}: {}", runDir.getFileName(), e.getMessage());
            return null;
        }
    }

    private VideoAnalysis loadFromLegacyDraft(Path runDir, Path draftFile) {
        try {
            ProcedureDraft draft = loadProcedureDraft(runDir, draftFile);
            if (draft == null) return null;
            return VideoAnalysisCompat.fromDraft(draft);
        } catch (Exception e) {
            log.warn("Kunde inte konvertera gammal körning {}: {}", runDir.getFileName(), e.getMessage());
            return null;
        }
    }

    private ProcedureDraft loadProcedureDraft(Path runDir, Path jsonFile) {
        try {
            JsonNode node = JSON.readTree(Files.readString(jsonFile));
            List<ProcedureStep> steps = new ArrayList<>();
            JsonNode stepsNode = node.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode s : stepsNode) {
                    String kindStr = s.path("kind").asText("OTHER");
                    StepKind kind;
                    try { kind = StepKind.valueOf(kindStr.toUpperCase()); } catch (Exception e) { kind = StepKind.OTHER; }
                    String frameStr = s.path("frameImage").asText("");
                    Path framePath = null;
                    if (!frameStr.isBlank()) {
                        String fileName = Path.of(frameStr).getFileName().toString();
                        Path candidate = runDir.resolve("frames").resolve(fileName).normalize();
                        if (candidate.startsWith(runDir) && Files.exists(candidate)) framePath = candidate;
                    }
                    steps.add(new ProcedureStep(
                            s.path("number").asInt(0),
                            s.path("title").asText(""),
                            s.path("instruction").asText(""),
                            kind,
                            s.path("target").asText(""),
                            s.has("inputText") && !s.get("inputText").isNull() ? s.get("inputText").asText() : null,
                            s.path("expectedResult").asText(""),
                            s.path("startSecond").asInt(0),
                            s.path("endSecond").asInt(0),
                            framePath,
                            s.path("uncertain").asBoolean(false)));
                }
            }
            List<String> prereqs = new ArrayList<>();
            if (node.path("prerequisites").isArray()) node.path("prerequisites").forEach(n -> prereqs.add(n.asText()));
            List<String> warnings = new ArrayList<>();
            if (node.path("warnings").isArray()) node.path("warnings").forEach(n -> warnings.add(n.asText()));
            return new ProcedureDraft(
                    node.path("title").asText(""),
                    node.path("purpose").asText(""),
                    prereqs,
                    steps,
                    warnings,
                    node.path("sourceFilename").asText(""),
                    node.path("model").asText(""),
                    runDir,
                    Map.of());
        } catch (Exception e) {
            log.warn("Kunde inte läsa procedure-draft.json: {}", e.getMessage());
            return null;
        }
    }

    public List<String> listFrames(String runId) {
        Path runDir = resolveRunDir(runId);
        if (runDir == null) return List.of();
        Path framesDir = runDir.resolve("frames");
        if (!Files.exists(framesDir)) return List.of();
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(framesDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("Kunde inte lista frames för {}: {}", runId, e.getMessage());
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public Path resolveFrame(String runId, String file) {
        if (!isValidRunId(runId)) return null;
        if (file == null || file.isBlank()) return null;
        // validate filename pattern (schema image pattern)
        if (!file.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.(png|jpe?g)$")) return null;
        Path runDir = resolveRunDir(runId);
        if (runDir == null) return null;
        Path target = runDir.resolve("frames").resolve(file).normalize();
        if (!target.startsWith(runDir.resolve("frames").normalize())) return null;
        if (!Files.exists(target)) return null;
        return target;
    }

    public List<String> listRuns() {
        List<String> ids = new ArrayList<>();
        if (!Files.exists(outputDir)) return ids;
        try (var stream = Files.list(outputDir)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                // Sök efter nytt format (analysis.json) eller gammalt format (procedure-draft.json)
                if (Files.exists(d.resolve("analysis.json")) || Files.exists(d.resolve("procedure-draft.json"))) {
                    ids.add(d.getFileName().toString());
                }
            });
        } catch (IOException e) {
            log.warn("Kunde inte lista körningar: {}", e.getMessage());
        }
        ids.sort(Comparator.reverseOrder());
        return ids;
    }

    public boolean delete(String runId) {
        Path runDir = resolveRunDir(runId);
        if (runDir == null || !Files.exists(runDir)) return false;
        try (var walk = Files.walk(runDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
            return true;
        } catch (IOException e) {
            log.warn("Kunde inte radera körning {}: {}", runId, e.getMessage());
            return false;
        }
    }

    public Path getOutputDir() { return outputDir; }
}
