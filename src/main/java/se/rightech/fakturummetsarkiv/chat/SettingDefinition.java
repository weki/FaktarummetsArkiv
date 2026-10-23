package se.rightech.fakturummetsarkiv.chat;

import java.util.List;

public record SettingDefinition(
        String key,
        String group,
        String label,
        String description,
        SettingsType type,
        Object defaultValue,
        String unit,
        Double min,
        Double max,
        Double step,
        List<String> allowedValues,
        ApplyMode applyMode,
        String dependsOn,
        String warning,
        boolean readOnly,
        boolean secret) {

    public boolean editable() {
        return !readOnly;
    }
}
