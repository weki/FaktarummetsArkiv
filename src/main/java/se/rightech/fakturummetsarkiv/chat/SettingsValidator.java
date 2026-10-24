package se.rightech.fakturummetsarkiv.chat;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class SettingsValidator {

    private final SettingsSchema schema;
    private final EffectiveSettings effective;

    public SettingsValidator(SettingsSchema schema, EffectiveSettings effective) {
        this.schema = schema;
        this.effective = effective;
    }

    public Map<String, Object> normalize(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw new SettingsValidationException(null, "Inga inställningar angivna");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            SettingDefinition definition = schema.require(entry.getKey());
            if (definition.readOnly()) {
                throw new ReadOnlySettingException(definition.key());
            }
            if (entry.getValue() == null) {
                normalized.put(definition.key(), null);
                continue;
            }
            normalized.put(definition.key(), convert(definition, entry.getValue()));
        }
        validateModelBudget(normalized);
        validateChunking(normalized);
        return normalized;
    }

    private Object convert(SettingDefinition definition, Object raw) {
        try {
            return switch (definition.type()) {
                case BOOLEAN -> asBoolean(definition, raw);
                case INTEGER -> asInteger(definition, raw);
                case DECIMAL -> asDecimal(definition, raw);
                case ENUM -> asEnum(definition, raw);
                case TEXT, MODEL, DURATION, BYTES, PATH -> asText(definition, raw);
            };
        } catch (NumberFormatException exception) {
            throw new SettingsValidationException(definition.key(), definition.label() + " måste vara ett tal");
        }
    }

    private Boolean asBoolean(SettingDefinition definition, Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = String.valueOf(raw).trim().toLowerCase();
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.parseBoolean(text);
        }
        throw new SettingsValidationException(definition.key(), definition.label() + " måste vara true eller false");
    }

    private Integer asInteger(SettingDefinition definition, Object raw) {
        int value;
        if (raw instanceof Number number) {
            if (number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new SettingsValidationException(definition.key(), definition.label() + " måste vara ett heltal");
            }
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw).trim());
        }
        checkRange(definition, value);
        return value;
    }

    private Double asDecimal(SettingDefinition definition, Object raw) {
        double value = raw instanceof Number number ? number.doubleValue()
                : Double.parseDouble(String.valueOf(raw).trim());
        checkRange(definition, value);
        return value;
    }

    private String asEnum(SettingDefinition definition, Object raw) {
        String value = String.valueOf(raw).trim();
        if (!definition.allowedValues().contains(value)) {
            throw new SettingsValidationException(definition.key(),
                    definition.label() + " måste vara något av: " + String.join(", ", definition.allowedValues()));
        }
        return value;
    }

    private String asText(SettingDefinition definition, Object raw) {
        String value = String.valueOf(raw);
        if (value.length() > 2000) {
            throw new SettingsValidationException(definition.key(), definition.label() + " är för långt");
        }
        return value.trim();
    }

    private void checkRange(SettingDefinition definition, double value) {
        if (definition.min() != null && value < definition.min()) {
            throw new SettingsValidationException(definition.key(),
                    definition.label() + " måste vara minst " + definition.min());
        }
        if (definition.max() != null && value > definition.max()) {
            throw new SettingsValidationException(definition.key(),
                    definition.label() + " måste vara högst " + definition.max());
        }
    }

    private void validateModelBudget(Map<String, Object> patch) {
        Set<String> models = new LinkedHashSet<>();
        for (String key : modelKeys()) {
            String value = patch.containsKey(key) && patch.get(key) != null
                    ? String.valueOf(patch.get(key))
                    : effective.string(key);
            if (value != null && !value.isBlank()) {
                models.add(value.trim());
            }
        }
        if (models.size() > 3) {
            throw new SettingsValidationException("ollama.model",
                    "Max 3 olika modeller får användas samtidigt (valda: " + String.join(", ", models) + ")");
        }
    }

    private void validateChunking(Map<String, Object> patch) {
        int size = patch.containsKey("rag.chunk-size") && patch.get("rag.chunk-size") != null
                ? Integer.parseInt(String.valueOf(patch.get("rag.chunk-size")))
                : effective.integer("rag.chunk-size");
        int overlap = patch.containsKey("rag.chunk-overlap") && patch.get("rag.chunk-overlap") != null
                ? Integer.parseInt(String.valueOf(patch.get("rag.chunk-overlap")))
                : effective.integer("rag.chunk-overlap");
        if (overlap >= size) {
            throw new SettingsValidationException("rag.chunk-overlap",
                    "Chunköverlapp måste vara mindre än chunkstorleken");
        }
    }

    private static Set<String> modelKeys() {
        return Set.of("ollama.model", "ollama.validator-model", "ollama.embedding-model",
                "ollama.refine-model", "ollama.gate-model", "ollama.rewrite-model", "rag.rerank.model");
    }
}
