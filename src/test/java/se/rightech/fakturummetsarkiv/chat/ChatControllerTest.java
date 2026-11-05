package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatControllerTest {

    @Test
    void doesNotLeakInternalErrorDetails() {
        ChatController controller = new ChatController(null, null, null, null, null, null, null, null, null);

        Map<String, String> response = controller.handleUnavailable(
                new RestClientException("internt fel: http://hemlig-host:11434"));

        assertThat(response).containsOnlyKeys("error");
        assertThat(response.get("error")).doesNotContain("hemlig-host");
    }

    @Test
    void handlesBadRequestForInvalidInput() {
        ChatController controller = new ChatController(null, null, null, null, null, null, null, null, null);

        Map<String, String> response = controller.handleBadRequest(
                new IllegalArgumentException("Ogiltig indata"));

        assertThat(response).containsOnlyKeys("error");
        assertThat(response.get("error")).isEqualTo("Ogiltig indata");
    }
}
