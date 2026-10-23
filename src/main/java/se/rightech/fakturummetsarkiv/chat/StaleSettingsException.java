package se.rightech.fakturummetsarkiv.chat;

public class StaleSettingsException extends RuntimeException {

    public StaleSettingsException() {
        super("Inställningarna har ändrats av någon annan. Läs in sidan igen.");
    }
}
