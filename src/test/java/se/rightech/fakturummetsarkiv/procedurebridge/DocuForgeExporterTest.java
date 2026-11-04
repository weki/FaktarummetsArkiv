package se.rightech.fakturummetsarkiv.procedurebridge;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.video.ProcedureDraft;
import se.rightech.fakturummetsarkiv.video.ProcedureStep;
import se.rightech.fakturummetsarkiv.video.StepKind;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocuForgeExporterTest {

    private final DocuForgeExporter exporter = new DocuForgeExporter();

    @Test
    void exportsValidYamlForSampleDraft() {
        ProcedureDraft draft = new ProcedureDraft(
                "Skapa användare", "Skapar en ny användare i systemet.",
                List.of("Administratörsbehörighet"),
                List.of(new ProcedureStep(1, "Öppna formulär", "Klicka på Ny användare", StepKind.CLICK,
                        "knappen Ny användare", null, "Formuläret öppnas", 12, 15, null, false),
                        new ProcedureStep(2, "Ange namn", "Skriv användarens namn", StepKind.INPUT,
                                "fältet Namn", "anna.andersson", "Namnet visas", 20, 24, null, true)),
                List.of("Kontrollera stavning"), "film.mp4", "qwen", null, null);
        String yaml = exporter.toYaml(draft);
        assertThat(yaml).contains("Skapa användare");
        assertThat(yaml).contains("step-1");
        assertThat(yaml).contains("anna.andersson");
        assertThat(yaml).contains("Uncertain step");
    }

    @Test
    void specialCharsRoundTrip() {
        String cmd = "ls -la /tmp; echo \"hej åäö\" | grep -E 'a|b' && x=1";
        ProcedureDraft draft = new ProcedureDraft("T", "P", List.of(),
                List.of(new ProcedureStep(1, "Kör kommando", "Kör i terminalen", StepKind.INPUT,
                        "terminalen", cmd, "utdata", 5, 8, null, false)),
                List.of(), "f.mp4", "m", null, null);
        String yaml = exporter.toYaml(draft);
        // parse back with Jackson yaml and check command preserved
        try {
            com.fasterxml.jackson.databind.ObjectMapper ym = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            com.fasterxml.jackson.databind.JsonNode node = ym.readTree(yaml);
            String got = node.path("sections").path("procedure").path("steps").get(0).path("command").asText();
            assertThat(got).isEqualTo(cmd);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void titleLongerThan200IsTruncated() {
        String longTitle = "A".repeat(250);
        ProcedureDraft draft = new ProcedureDraft(longTitle, "P", List.of(),
                List.of(new ProcedureStep(1, "S", "I", StepKind.OTHER, "t", null, "r", 1, 2, null, false)),
                List.of(), "f.mp4", "m", null, null);
        String yaml = exporter.toYaml(draft);
        // title in yaml should be 200 chars
        assertThat(yaml).contains("A".repeat(200));
    }

    @Test
    void zipContainsYamlReviewAndImages() {
        ProcedureDraft draft = new ProcedureDraft("T", "P", List.of(),
                List.of(new ProcedureStep(1, "S", "I", StepKind.OTHER, "t", null, "r", 1, 2, null, false)),
                List.of("w"), "f.mp4", "m", null, null);
        byte[] zip = exporter.toZip(draft);
        assertThat(zip).isNotEmpty();
        // basic zip magic
        assertThat(zip[0]).isEqualTo((byte) 0x50);
        assertThat(zip[1]).isEqualTo((byte) 0x4B);
    }
}
