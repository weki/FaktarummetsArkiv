package se.rightech.fakturummetsarkiv.chat;

public class SettingsAccessDeniedException extends RuntimeException {
    public SettingsAccessDeniedException(String message) {
        super(message);
    }
}
