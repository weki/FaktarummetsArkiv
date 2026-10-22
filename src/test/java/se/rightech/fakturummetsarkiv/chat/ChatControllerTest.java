package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatControllerTest {

    @Test
    void doesNotLeakInternalErrorDetails() {
        ChatController controller = new ChatController(null, null, null, null, null, null);

        Map<String, String> response = controller.handleUnavailable(
                new RestClientException("internt fel: http://hemlig-host:11434"));

        assertThat(response).containsOnlyKeys("error");
        assertThat(response.get("error")).doesNotContain("hemlig-host");
    }
}
