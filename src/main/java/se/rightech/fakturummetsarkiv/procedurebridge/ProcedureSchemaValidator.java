package se.rightech.fakturummetsarkiv.procedurebridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class ProcedureSchemaValidator {

    public static final int MAX_YAML_CHARS = 200_000;

    public record ValidationResult(boolean valid, JsonNode parsedTree, List<String> errors) {
    }

    private final JsonSchema schema;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ProcedureSchemaValidator() {
        try {
            JsonNode schemaNode = json.readTree(new ClassPathResource("procedurebridge/procedure-schema.json").getInputStream());
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.schema = factory.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("Kunde inte ladda procedure-schema.json: " + e.getMessage(), e);
        }
    }

    public ValidationResult validate(String yamlText, List<Path> availableFrames) {
        List<String> errors = new ArrayList<>();
        if (yamlText == null) {
            errors.add("YAML är null");
            return new ValidationResult(false, null, errors);
        }
        if (yamlText.length() > MAX_YAML_CHARS) {
            errors.add("YAML överskrider maxlängd (" + MAX_YAML_CHARS + " tecken)");
            return new ValidationResult(false, null, errors);
        }
        // anchor/alias heuristic
        if (yamlText.matches("(?s).*[&*][A-Za-z_].*") && yamlText.matches("(?m)(^|[\\{\\[:,])\\s*[&*][A-Za-z_].*")) {
            errors.add("YAML innehåller ankare/alias (ej tillåtet)");
        }
        // multi-document check
        int docs = 0;
        try (com.fasterxml.jackson.core.JsonParser p = yaml.createParser(yamlText)) {
            while (p.nextToken() != null) {
                // count top-level documents is not directly available; instead check for '---' separator
            }
        } catch (Exception ignored) {
        }
        if (yamlText.lines().anyMatch(l -> l.trim().equals("---") || l.trim().startsWith("--- "))) {
            // allow a single leading '---'
            long sep = yamlText.lines().filter(l -> l.trim().equals("---") || l.trim().startsWith("--- ")).count();
            if (sep > 1) errors.add("YAML innehåller flera dokument");
        }

        JsonNode tree;
        try {
            tree = yaml.readTree(yamlText);
        } catch (Exception e) {
            errors.add("YAML kunde inte parsas: " + e.getMessage());
            return new ValidationResult(false, null, errors);
        }
        if (tree == null) {
            errors.add("YAML är tomt");
            return new ValidationResult(false, null, errors);
        }

        Set<ValidationMessage> schemaErrors = schema.validate(tree);
        for (ValidationMessage m : schemaErrors) {
            errors.add(m.getInstanceLocation() + ": " + m.getMessage());
        }

        // extra: image files must exist among available frames
        if (availableFrames != null) {
            List<String> availableNames = new ArrayList<>();
            for (Path p : availableFrames) availableNames.add(p.getFileName().toString());
            checkImages(tree.path("sections"), availableNames, errors);
        }

        return new ValidationResult(errors.isEmpty(), tree, errors);
    }

    private void checkImages(JsonNode sections, List<String> availableNames, List<String> errors) {
        if (sections == null || !sections.isObject()) return;
        for (String key : List.of("procedure", "scope", "appendix", "contingency", "references", "troubleshooting")) {
            JsonNode sec = sections.path(key);
            collectImages(sec, availableNames, errors, "sections." + key);
        }
        // nested subprocedures
    }

    private void collectImages(JsonNode node, List<String> availableNames, List<String> errors, String path) {
        if (node == null || node.isMissingNode()) return;
        if (node.has("images") && node.get("images").isArray()) {
            int i = 0;
            for (JsonNode img : node.get("images")) {
                String file = img.path("file").asText("");
                if (!file.isBlank() && !availableNames.contains(file)) {
                    errors.add(path + ".images[" + i + "].file: bildfilen finns inte bland körningens bildrutor (" + file + ")");
                }
                i++;
            }
        }
        if (node.has("steps") && node.get("steps").isArray()) {
            int i = 0;
            for (JsonNode step : node.get("steps")) {
                collectImages(step, availableNames, errors, path + ".steps[" + i + "]");
                i++;
            }
        }
        if (node.has("subprocedures") && node.get("subprocedures").isArray()) {
            int i = 0;
            for (JsonNode sp : node.get("subprocedures")) {
                collectImages(sp, availableNames, errors, path + ".subprocedures[" + i + "]");
                i++;
            }
        }
    }
}
