package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

class ProcedureIntentTest {
    // Medvetna avvägningar:
    // - "Dela upp" och "dela" behandlas likadant (båda är EDIT med procedur)
    // - "Ta" utan "bort" eller "fram" är inte redigeringsverb
    // - Bekräftelser kräver PROPOSAL_MARKER i senaste svar för EDIT, annars DISCUSS
    // - "nej" och avslag är alltid DISCUSS med procedur, aldrig EDIT

    static Stream<Arguments> intentTable() {
        return Stream.of(
            // R1-tabell (grundläggande)
            Arguments.of("Vad händer i videon?", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Är det här en procedur?", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Sammanfatta inspelningen", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Förklara steg för steg vad som görs", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Vilken adress används i videon?", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Skapa en procedur av videon", false, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Gör en SOP enligt schemat", false, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Skriv ner det här som YAML", false, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Kan du skapa en procedur av det här?", false, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Create a procedure from the video", false, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Börja om från videon", true, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Skapa proceduren på nytt", true, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Slå ihop steg 4 och 5", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Lägg till en varning i steg 3", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Kan du lägga till en varning i steg 3?", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Skriv den för operatörer, inte tekniker", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Gör den kortare", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Ta bort inloggningsstegen", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Gör steg 6 kritiskt", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Ändra titeln till \"Restart OPC UA\"", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Dela upp steg 4", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Merge steps 2 and 3", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Borde vi dela upp steg 4?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Varför ändrade du steg 3?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Varför har steg 3 en varning?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Vad händer om steg 5 misslyckas?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Är steg 2 nödvändigt?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Hur länge tar steg 7?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Vilken adress står i steg 5?", true, true, null, ProcedureIntent.Intent.DISCUSS),
            Arguments.of("Visa YAML", true, true, null, ProcedureIntent.Intent.SHOW),
            Arguments.of("Skriv ut proceduren", true, true, null, ProcedureIntent.Intent.SHOW),
            Arguments.of("Show me the YAML", true, true, null, ProcedureIntent.Intent.SHOW),
            Arguments.of("Visa YAML", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("hej", true, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("tack", true, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("ja, gör det", true, true, "Say the word and I'll apply it", ProcedureIntent.Intent.EDIT),
            Arguments.of("ja, gör det", true, true, "vanlig förklaring", ProcedureIntent.Intent.DISCUSS),
            Arguments.of("nej", true, true, "förslag", ProcedureIntent.Intent.DISCUSS),

            // S1-tillägg: bestämd form och böjningar
            Arguments.of("Skapa proceduren på nytt", true, true, null, ProcedureIntent.Intent.CREATE),
            Arguments.of("Visa proceduren", true, true, null, ProcedureIntent.Intent.SHOW),
            Arguments.of("Skriv ut proceduren", true, true, null, ProcedureIntent.Intent.SHOW),
            Arguments.of("Show me the procedure", true, true, null, ProcedureIntent.Intent.SHOW),
            Arguments.of("Skulle du kunna slå ihop steg 2 och 3?", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Could you split step 4?", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("nej tack", true, true, "förslag", ProcedureIntent.Intent.DISCUSS),
            Arguments.of("tack", true, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Vi kan ta det senare", true, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Delarna ser bra ut", true, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Vad är en SOP?", false, true, null, ProcedureIntent.Intent.NONE),
            Arguments.of("Skriv ett mejl till Anna om videon", false, true, null, ProcedureIntent.Intent.NONE),

            // Rättning: skriv/write utan "ut" är EDIT, inte SHOW
            Arguments.of("Skriv proceduren för operatörer", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Skriv om proceduren", true, true, null, ProcedureIntent.Intent.EDIT),
            Arguments.of("Write the procedure for operators", true, true, null, ProcedureIntent.Intent.EDIT)
        );
    }

    @ParameterizedTest
    @MethodSource("intentTable")
    void classifyWithTable(String message, boolean hasProcedure, boolean hasAttachment, String lastAssistant, ProcedureIntent.Intent expected) {
        assertThat(ProcedureIntent.classify(message, hasProcedure, hasAttachment, lastAssistant))
            .as("Meddelande: '%s' (procedur=%s, bilaga=%s)", message, hasProcedure, hasAttachment)
            .isEqualTo(expected);
    }
}
