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

    private final ProcedureSchemaValidator validator;

    public DocuForgeExporter(ProcedureSchemaValidator validator) {
        this.validator = validator;
    }

    public DocuForgeExporter() {
        this(new ProcedureSchemaValidator());
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
        ProcedureSchemaValidator.ValidationResult vr = validator.validate(yaml, null);
        if (!vr.valid()) {
            throw new ExportException("YAML validerades inte mot schemat:\n - " + String.join("\n - ", vr.errors()));
        }
        return yaml;
    }

    public String toReviewMarkdown(ProcedureDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Review report – ").append(draft.title()).append("\n\n");
        sb.append("Source file: ").append(draft.sourceFilename()).append("\n");
        sb.append("Model: ").append(draft.model()).append("\n\n");
        if (draft.warnings() != null && !draft.warnings().isEmpty()) {
            sb.append("## Warnings\n");
            for (String w : draft.warnings()) sb.append("- ").append(w).append("\n");
            sb.append("\n");
        }
        sb.append("## Steps\n");
        for (ProcedureStep s : draft.steps()) {
            sb.append(s.number()).append(". ").append(s.title())
                    .append(" (").append(formatTime(s.startSecond())).append("–").append(formatTime(s.endSecond())).append(")\n");
            if (s.uncertain()) sb.append("   ⚠ UNCERTAIN - verify against the recording\n");
            if (s.inputText() != null && !s.inputText().isBlank())
                sb.append("   Input: ").append(s.inputText()).append("\n");
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
        String title = draft.title() == null || draft.title().isBlank() ? "Procedure draft" : draft.title();
        if (title.length() > 200) title = title.substring(0, 200);
        metadata.put("title", title);
        root.put("metadata", metadata);

        Map<String, Object> sections = new LinkedHashMap<>();
        if (draft.purpose() != null && !draft.purpose().isBlank()) {
            Map<String, Object> purpose = new LinkedHashMap<>();
            purpose.put("title", "Purpose");
            purpose.put("content", draft.purpose());
            sections.put("purpose", purpose);
        }
        if (draft.prerequisites() != null && !draft.prerequisites().isEmpty()) {
            Map<String, Object> prereq = new LinkedHashMap<>();
            prereq.put("title", "Prerequisites");
            prereq.put("items", draft.prerequisites());
            sections.put("prerequisites", prereq);
        }
        Map<String, Object> procedure = new LinkedHashMap<>();
        procedure.put("title", "Procedure");
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
                desc.append("Expected result: ").append(s.expectedResult());
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
                step.put("warnings", List.of("Uncertain step - verify against the recording."));
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
