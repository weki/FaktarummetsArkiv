package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String rewriteModel) {
        return new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "", rewriteModel, 16384, 4096);
    }

    private static List<Map<String, String>> history() {
        return List.of(
                Map.of("role", "user", "content", "Vad är OPC UA?"),
                Map.of("role", "assistant", "content", "OPC UA är ett protokoll..."));
    }

    @Test
    void rewritesUsingHistory() {
        when(client.chat(anyString(), anyString(), anyString()))
                .thenReturn("Hur aktiverar jag OPC UA på en server?");
        String result = new QueryRewriter(client, props("")).rewrite(
                "hur aktiverar jag det på en server?", history(), "gemma4:latest");
        assertThat(result).isEqualTo("Hur aktiverar jag OPC UA på en server?");
    }

    @Test
    void rewritesWithoutHistoryToTheSearchLanguage() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("OPC UA overview");
        String result = new QueryRewriter(client, props("")).rewrite(
                "vad är OPC UA?", List.of(), "gemma4:latest", "en");
        assertThat(result).isEqualTo("OPC UA overview");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).chat(anyString(), anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("SEARCH LANGUAGE: en")
                .contains("vad är OPC UA?");
    }

    @Test
    void fallsBackToOriginalOnError() {
        when(client.chat(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("nere"));
        String result = new QueryRewriter(client, props("")).rewrite("hej där", history(), "gemma4:latest");
        assertThat(result).isEqualTo("hej där");
    }

    @Test
    void prefersExplicitModelAndFallsBackToPropertyModel() {
        QueryRewriter rewriter = new QueryRewriter(client, props("small-model"));
        assertThat(rewriter.pickModel("gemma4:latest")).isEqualTo("gemma4:latest");
        assertThat(rewriter.pickModel(null)).isEqualTo("small-model");
    }

    @Test
    void cleansQuotesAndPreamble() {
        assertThat(QueryRewriter.clean("Standalone search query: \"OPC UA server aktivering?\"\n"))
                .isEqualTo("OPC UA server aktivering?");
    }
}
