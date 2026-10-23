package se.rightech.fakturummetsarkiv.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettingsService {

    private final SettingsSchema schema;
    private final EffectiveSettings effective;
    private final SettingsValidator validator;
    private final ConfigurableEnvironment environment;
    private final SettingsFileStore fileStore;

    @Autowired
    public SettingsService(SettingsSchema schema, EffectiveSettings effective, SettingsValidator validator,
                           ConfigurableEnvironment environment, SettingsFileStore fileStore) {
        this.schema = schema;
        this.effective = effective;
        this.validator = validator;
        this.environment = environment;
        this.fileStore = fileStore;
    }

    SettingsService(SettingsSchema schema, EffectiveSettings effective,
                    SettingsValidator validator, ConfigurableEnvironment environment) {
        this(schema, effective, validator, environment, null);
    }

    public SettingsSnapshot snapshot() {
        List<SettingView> settings = new ArrayList<>();
        for (SettingDefinition definition : schema.definitions()) {
            settings.add(view(definition));
        }
        return new SettingsSnapshot(effective.revision(), schema.groups(), settings,
                Map.copyOf(effective.overrides()), fileStore == null ? null : fileStore.path().toString());
    }

    public synchronized SettingsSnapshot update(Map<String, Object> values, Long expectedVersion) {
        checkVersion(expectedVersion);
        Map<String, Object> normalized = validator.normalize(values);
        Map<String, Object> merged = new LinkedHashMap<>(effective.overrides());
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            if (entry.getValue() == null) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        persist(merged);
        effective.replaceOverrides(merged);
        return snapshot();
    }

    public synchronized SettingsSnapshot reset(String key, Long expectedVersion) {
        checkVersion(expectedVersion);
        SettingDefinition definition = schema.require(key);
        if (definition.readOnly()) {
            throw new ReadOnlySettingException(key);
        }
        Map<String, Object> merged = new LinkedHashMap<>(effective.overrides());
        merged.remove(key);
        persist(merged);
        effective.replaceOverrides(merged);
        return snapshot();
    }

    public synchronized SettingsSnapshot resetAll(Long expectedVersion) {
        checkVersion(expectedVersion);
        persist(Map.of());
        effective.replaceOverrides(Map.of());
        return snapshot();
    }

    private void persist(Map<String, Object> overrides) {
        if (fileStore != null) {
            fileStore.save(overrides);
        }
    }

    private void checkVersion(Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != effective.revision()) {
            throw new StaleSettingsException();
        }
    }

    private SettingView view(SettingDefinition definition) {
        Object value = effective.value(definition.key());
        return new SettingView(
                definition.key(),
                definition.group(),
                definition.label(),
                definition.description(),
                definition.type(),
                value,
                definition.defaultValue(),
                source(definition.key()),
                definition.applyMode(),
                definition.unit(),
                definition.min(),
                definition.max(),
                definition.step(),
                definition.allowedValues(),
                definition.dependsOn(),
                definition.warning(),
                definition.readOnly(),
                definition.secret(),
                effective.overrides().containsKey(definition.key()));
    }

    private String source(String key) {
        if (effective.overrides().containsKey(key)) {
            return "ui";
        }
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            names.add(source.getName());
        }
        for (String name : names) {
            PropertySource<?> source = environment.getPropertySources().get(name);
            if (source != null && source.containsProperty(key)) {
                String lower = name.toLowerCase();
                if (lower.contains("application-local")) {
                    return "file";
                }
                if (lower.contains("systemenvironment") || lower.contains("systemproperties")) {
                    return "env";
                }
            }
        }
        return "default";
    }

    public record SettingsSnapshot(
            long version,
            List<SettingsGroup> groups,
            List<SettingView> settings,
            Map<String, Object> overrides,
            String file) {
    }

    public record SettingView(
            String key,
            String group,
            String label,
            String description,
            SettingsType type,
            Object value,
            Object defaultValue,
            String source,
            ApplyMode applyMode,
            String unit,
            Double min,
            Double max,
            Double step,
            List<String> allowedValues,
            String dependsOn,
            String warning,
            boolean readOnly,
            boolean secret,
            boolean overridden) {
    }
}
