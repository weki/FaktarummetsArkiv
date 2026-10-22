package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentControllerTest {

    @Test
    void doesNotLeakInternalErrorDetails() {
        DocumentController controller = new DocumentController(null, null);

        Map<String, String> response = controller.handleUnavailable(
                new IllegalStateException("internt fel: /srv/hemligt/dokument.pdf"));

        assertThat(response.get("error"))
                .isEqualTo("Tjänsten är inte tillgänglig just nu")
                .doesNotContain("hemligt");
    }
}
