package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmRerankerTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static final OllamaProperties OLLAMA = new OllamaProperties(
            "http://localhost:11434", "qwen3:8b", "e5", Duration.ofSeconds(5),
            "qwen3:8b", true, "qwen3:8b", "phi3.5:latest", "", 16384, 4096, false, false, false, false, false, null);

    private static final RerankProperties PROPS = new RerankProperties(true, 25, "rerank-model", 600);

    private static List<Map<String, Object>> candidates() {
        return List.of(
                Map.of("title", "a", "text", "alpha"),
                Map.of("title", "b", "text", "beta"),
                Map.of("title", "c", "text", "gamma"));
    }

    @Test
    void reordersByModelOutputAndKeepsTopK() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("3, 1, 2");

        List<Map<String, Object>> result = new LlmReranker(client, OLLAMA, PROPS)
                .rerank("beta", candidates(), 2);

        assertThat(result).extracting(candidate -> candidate.get("title")).containsExactly("c", "a");
    }

    @Test
    void appendsMissingCandidatesInOriginalOrder() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("3");

        List<Map<String, Object>> result = new LlmReranker(client, OLLAMA, PROPS)
                .rerank("beta", candidates(), 3);

        assertThat(result).extracting(candidate -> candidate.get("title")).containsExactly("c", "a", "b");
    }

    @Test
    void keepsOriginalOrderWhenResponseIsUnparseable() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("no idea");

        List<Map<String, Object>> result = new LlmReranker(client, OLLAMA, PROPS)
                .rerank("beta", candidates(), 3);

        assertThat(result).extracting(candidate -> candidate.get("title")).containsExactly("a", "b", "c");
    }

    @Test
    void keepsOriginalOrderWhenModelFails() {
        when(client.chat(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("nere"));

        List<Map<String, Object>> result = new LlmReranker(client, OLLAMA, PROPS)
                .rerank("beta", candidates(), 3);

        assertThat(result).extracting(candidate -> candidate.get("title")).containsExactly("a", "b", "c");
    }

    @Test
    void skipsLlmForSingleCandidate() {
        List<Map<String, Object>> result = new LlmReranker(client, OLLAMA, PROPS)
                .rerank("beta", List.of(Map.of("title", "a", "text", "alpha")), 3);

        assertThat(result).hasSize(1);
        verifyNoInteractions(client);
    }

    @Test
    void promptListsNumberedCandidates() {
        String prompt = new LlmReranker(client, OLLAMA, PROPS).buildPrompt("beta", candidates());

        assertThat(prompt).contains("QUESTION:\nbeta").contains("[1] a: alpha").contains("[3] c: gamma");
    }
}
