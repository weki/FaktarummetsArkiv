package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProcedureIntentTest {
    @Test
    void table() {
        assertThat(ProcedureIntent.classify("Vad händer i videon?", false, true, null)).isEqualTo(ProcedureIntent.Intent.NONE);
        assertThat(ProcedureIntent.classify("Är det här en procedur?", false, true, null)).isEqualTo(ProcedureIntent.Intent.NONE);
        assertThat(ProcedureIntent.classify("Skapa en procedur av videon", false, true, null)).isEqualTo(ProcedureIntent.Intent.CREATE);
        assertThat(ProcedureIntent.classify("Gör en SOP enligt schemat", false, true, null)).isEqualTo(ProcedureIntent.Intent.CREATE);
        assertThat(ProcedureIntent.classify("Skapa en procedur av videon", true, true, null)).isEqualTo(ProcedureIntent.Intent.CREATE);
        assertThat(ProcedureIntent.classify("Slå ihop steg 4 och 5", true, true, null)).isEqualTo(ProcedureIntent.Intent.EDIT);
        assertThat(ProcedureIntent.classify("Lägg till en varning i steg 3", true, true, null)).isEqualTo(ProcedureIntent.Intent.EDIT);
        assertThat(ProcedureIntent.classify("Varför har steg 3 en varning?", true, true, null)).isEqualTo(ProcedureIntent.Intent.DISCUSS);
        assertThat(ProcedureIntent.classify("Visa YAML", true, true, null)).isEqualTo(ProcedureIntent.Intent.SHOW);
        assertThat(ProcedureIntent.classify("hej", true, true, null)).isEqualTo(ProcedureIntent.Intent.NONE);
    }
}
