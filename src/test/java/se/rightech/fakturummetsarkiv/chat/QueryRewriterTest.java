package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String rewriteModel) {
        return new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "", rewriteModel, 16384, 4096, 2048, 2048, false, false, false, false, false, null);
    }

    private static List<Map<String, String>> history() {
        return List.of(
                Map.of("role", "user", "content", "Vad är OPC UA?"),
                Map.of("role", "assistant", "content", "OPC UA är ett protokoll..."));
    }

    @Test
    void rewritesUsingHistory() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("Hur aktiverar jag OPC UA på en server?");
        String result = new QueryRewriter(client, props("")).rewrite(
                "hur aktiverar jag det på en server?", history(), "gemma4:latest");
        assertThat(result).isEqualTo("Hur aktiverar jag OPC UA på en server?");
    }

    @Test
    void rewritesWithoutHistoryToTheSearchLanguage() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("OPC UA overview");
        String result = new QueryRewriter(client, props("")).rewrite(
                "vad är OPC UA?", List.of(), "gemma4:latest", "en");
        assertThat(result).isEqualTo("OPC UA overview");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).chat(anyString(), anyString(), prompt.capture(), anyDouble(), anyInt(), anyBoolean());
        assertThat(prompt.getValue())
                .contains("SEARCH LANGUAGE: en")
                .contains("vad är OPC UA?");
    }

    @Test
    void fallsBackToOriginalOnError() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenThrow(new RuntimeException("nere"));
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

    @Test
    void forwardsRewriteThinkingSetting() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("OPC UA overview");
        OllamaProperties thinking = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "e5", Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b", "qwen3:8b", "qwen3:8b",
                16384, 8192, 2048, 2048, false, false, false, true, false, null);

        new QueryRewriter(client, thinking).rewrite("vad är OPC UA?", List.of(), "qwen3:8b");

        org.mockito.ArgumentCaptor<Boolean> flag = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        verify(client).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), flag.capture());
        assertThat(flag.getValue()).isTrue();
    }

    @Test
    void usesDedicatedRewriteContextSize() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("OPC UA overview");

        new QueryRewriter(client, props("")).rewrite("vad är OPC UA?", List.of(), "gemma4:latest");

        org.mockito.ArgumentCaptor<Integer> numCtx = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(client).chat(anyString(), anyString(), anyString(), anyDouble(), numCtx.capture(),
                anyBoolean());
        assertThat(numCtx.getValue()).isEqualTo(2048);
    }

    @Test
    void longestAllowedPromptFitsRewriteContext() {
        List<Map<String, String>> history = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "content", "x".repeat(600)));
        }
        String question = "q".repeat(500);
        int numCtx = props("").rewriteNumCtx();
        String prompt = QueryRewriter.fullPrompt(history, question, "en");

        int estimatedTokens = (prompt.length() + 2) / 3;
        assertThat(estimatedTokens).isLessThan(numCtx - 256);
    }
}
