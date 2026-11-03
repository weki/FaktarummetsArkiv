package se.rightech.fakturummetsarkiv.procedurebridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.video.ProcedureDraft;
import se.rightech.fakturummetsarkiv.video.ProcedureStep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class DocuForgeExporter {

    private static final Logger log = LoggerFactory.getLogger(DocuForgeExporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final JsonSchema schema;

    public DocuForgeExporter() {
        try {
            ClassPathResource res = new ClassPathResource("procedurebridge/procedure-schema.json");
            JsonNode schemaNode = JSON.readTree(res.getInputStream());
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.schema = factory.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("Kunde inte ladda procedure-schema.json: " + e.getMessage(), e);
        }
    }

    public static class ExportException extends RuntimeException {
        public ExportException(String message) { super(message); }
    }

    public String toYaml(ProcedureDraft draft) {
        Map<String, Object> root = buildModel(draft);
        String yaml;
        try {
            yaml = YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new ExportException("Kunde inte serialisera YAML: " + e.getMessage());
        }
        // Validate
        try {
            JsonNode instance = YAML.readTree(yaml);
            Set<ValidationMessage> errors = schema.validate(instance);
            if (!errors.isEmpty()) {
                StringBuilder sb = new StringBuilder("YAML validerades inte mot schemat:");
                for (ValidationMessage m : errors) sb.append("\n - ").append(m.getMessage());
                throw new ExportException(sb.toString());
            }
        } catch (ExportException e) {
            throw e;
        } catch (Exception e) {
            throw new ExportException("Validering misslyckades: " + e.getMessage());
        }
        return yaml;
    }

    public String toReviewMarkdown(ProcedureDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Granskningsrapport – ").append(draft.title()).append("\n\n");
        sb.append("Källfil: ").append(draft.sourceFilename()).append("\n");
        sb.append("Modell: ").append(draft.model()).append("\n\n");
        if (draft.warnings() != null && !draft.warnings().isEmpty()) {
            sb.append("## Varningar\n");
            for (String w : draft.warnings()) sb.append("- ").append(w).append("\n");
            sb.append("\n");
        }
        sb.append("## Steg\n");
        for (ProcedureStep s : draft.steps()) {
            sb.append(s.number()).append(". ").append(s.title())
                    .append(" (").append(formatTime(s.startSecond())).append("–").append(formatTime(s.endSecond())).append(")\n");
            if (s.uncertain()) sb.append("   ⚠ OSÄKERT – verifiera mot inspelningen\n");
            if (s.inputText() != null && !s.inputText().isBlank())
                sb.append("   Inmatning: ").append(s.inputText()).append("\n");
        }
        return sb.toString();
    }

    public byte[] toZip(ProcedureDraft draft) {
        String yaml = toYaml(draft);
        String review = toReviewMarkdown(draft);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            // procedure.yaml
            zos.putNextEntry(new ZipEntry("procedure.yaml"));
            zos.write(yaml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            // review.md
            zos.putNextEntry(new ZipEntry("review.md"));
            zos.write(review.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            // images
            for (ProcedureStep s : draft.steps()) {
                if (s.frameImage() == null) continue;
                Path img = s.frameImage();
                if (!Files.exists(img)) continue;
                String name = img.getFileName().toString();
                zos.putNextEntry(new ZipEntry(name));
                zos.write(Files.readAllBytes(img));
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new ExportException("Kunde inte bygga zip: " + e.getMessage());
        }
    }

    private Map<String, Object> buildModel(ProcedureDraft draft) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String title = draft.title() == null || draft.title().isBlank() ? "Procedurutkast" : draft.title();
        if (title.length() > 200) title = title.substring(0, 200);
        metadata.put("title", title);
        root.put("metadata", metadata);

        Map<String, Object> sections = new LinkedHashMap<>();
        if (draft.purpose() != null && !draft.purpose().isBlank()) {
            Map<String, Object> purpose = new LinkedHashMap<>();
            purpose.put("title", "Syfte");
            purpose.put("content", draft.purpose());
            sections.put("purpose", purpose);
        }
        if (draft.prerequisites() != null && !draft.prerequisites().isEmpty()) {
            Map<String, Object> prereq = new LinkedHashMap<>();
            prereq.put("title", "Förkrav");
            prereq.put("items", draft.prerequisites());
            sections.put("prerequisites", prereq);
        }
        Map<String, Object> procedure = new LinkedHashMap<>();
        procedure.put("title", "Procedur");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (ProcedureStep s : draft.steps()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", "step-" + s.number());
            String st = s.title() == null || s.title().isBlank() ? "Steg " + s.number() : s.title();
            step.put("title", st);
            StringBuilder desc = new StringBuilder();
            if (s.instruction() != null) desc.append(s.instruction());
            if (s.expectedResult() != null && !s.expectedResult().isBlank()) {
                if (desc.length() > 0) desc.append("\n\n");
                desc.append("Förväntat resultat: ").append(s.expectedResult());
            }
            if (desc.length() > 0) step.put("description", desc.toString());
            if (s.inputText() != null && !s.inputText().isBlank()) {
                step.put("command", s.inputText());
            }
            if (s.frameImage() != null) {
                Map<String, Object> img = new LinkedHashMap<>();
                img.put("file", s.frameImage().getFileName().toString());
                step.put("images", List.of(img));
            }
            if (s.uncertain()) {
                step.put("warnings", List.of("Osäkert steg – verifiera mot inspelningen."));
            }
            steps.add(step);
        }
        procedure.put("steps", steps);
        sections.put("procedure", procedure);
        root.put("sections", sections);
        return root;
    }

    private static String formatTime(int totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
