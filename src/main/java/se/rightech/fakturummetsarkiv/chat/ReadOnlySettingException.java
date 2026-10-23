package se.rightech.fakturummetsarkiv.chat;

public class ReadOnlySettingException extends RuntimeException {

    private final String field;

    public ReadOnlySettingException(String field) {
        super(field + " är skrivskyddad och kan bara ändras i konfigurationen");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
