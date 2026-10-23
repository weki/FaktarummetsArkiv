package se.rightech.fakturummetsarkiv.chat;

public class SettingsValidationException extends RuntimeException {

    private final String field;

    public SettingsValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
