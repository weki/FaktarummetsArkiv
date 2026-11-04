package se.rightech.fakturummetsarkiv.procedurebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.video.ProcedureDraft;
import se.rightech.fakturummetsarkiv.video.ProcedureStep;
import se.rightech.fakturummetsarkiv.video.StepKind;
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
                if (Files.exists(d.resolve("procedure-draft.json"))) {
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
