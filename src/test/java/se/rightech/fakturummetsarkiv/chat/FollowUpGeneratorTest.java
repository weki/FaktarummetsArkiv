package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FollowUpGeneratorTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String refineModel) {
        return new OllamaProperties("http://localhost:11434", "deepseek-r1:14b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, refineModel, "", "");
    }

    private static List<Map<String, Object>> sources() {
        return List.of(Map.of("title", "OPCUA_v15.pdf", "text", "OPC UA uses redundancy."));
    }

    @Test
    void generatesCleanedQuestions() {
        when(client.chat(eq("gemma4:latest"), any(), any())).thenReturn("""
                1. How does redundancy work?
                - What are the security requirements
                2. Which systems support OPC UA?
                """);
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        List<String> questions = generator.generate("Fraga", "OPC UA is redundant.", sources(), null, 3);

        assertThat(questions).containsExactly(
                "How does redundancy work?",
                "What are the security requirements?",
                "Which systems support OPC UA?");
    }

    @Test
    void refusesWhenModelSaysNone() {
        when(client.chat(any(), any(), any())).thenReturn("NONE");
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        assertThat(generator.generate("Fraga", "svar", sources(), null, 3)).isEmpty();
    }

    @Test
    void returnsEmptyWithoutSourcesOrAnswer() {
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        assertThat(generator.generate("Fraga", "svar", List.of(), null, 3)).isEmpty();
        assertThat(generator.generate("Fraga", "", sources(), null, 3)).isEmpty();
    }

    @Test
    void returnsEmptyOnError() {
        when(client.chat(any(), any(), any())).thenThrow(new RuntimeException("nere"));
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        assertThat(generator.generate("Fraga", "svar", sources(), null, 3)).isEmpty();
    }
}
