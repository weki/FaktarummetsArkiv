package se.rightech.fakturummetsarkiv.chat;

public record SettingsGroup(
        String id,
        String label,
        String description,
        int order) {
}
