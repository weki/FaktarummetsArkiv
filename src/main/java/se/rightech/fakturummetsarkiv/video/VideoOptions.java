package se.rightech.fakturummetsarkiv.video;

/**
 * Tillval för en videoanalys. {@code null}-fält betyder "använd default från konfigurationen".
 */
public record VideoOptions(String prompt, String language) {
}
