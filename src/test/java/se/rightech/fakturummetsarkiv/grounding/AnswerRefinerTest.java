package se.rightech.fakturummetsarkiv.grounding;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerRefinerTest {

    private final OllamaClient client = mock(OllamaClient.class);
    private static final RagProperties RAG = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");

    private static OllamaProperties props(String refineModel) {
        return new OllamaProperties("http://localhost:11434", "deepseek-r1:14b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, refineModel, "", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
    }

    private static List<Map<String, Object>> sources() {
        return List.of(Map.of("title", "dok", "text", "innehall"));
    }

    @Test
    void refinesUsingConfiguredModel() {
        when(client.chat(eq("gemma4:latest"), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("  grundat svar  ");
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"), RAG);

        assertThat(refiner.enabled()).isTrue();
        assertThat(refiner.refine("fraga", sources(), "utkast")).isEqualTo("grundat svar");
    }

    @Test
    void disabledWithoutModel() {
        AnswerRefiner refiner = new AnswerRefiner(client, props(""), RAG);

        assertThat(refiner.enabled()).isFalse();
        assertThat(refiner.refine("fraga", sources(), "utkast")).isNull();
    }

    @Test
    void returnsNullWithoutSources() {
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"), RAG);

        assertThat(refiner.refine("fraga", List.of(), "utkast")).isNull();
    }

    @Test
    void returnsNullOnError() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenThrow(new RuntimeException("nere"));
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"), RAG);

        assertThat(refiner.refine("fraga", sources(), "utkast")).isNull();
    }

    @Test
    void detectsRefusal() {
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"), RAG);

        assertThat(refiner.isRefusal("I cannot find that information in the documents.")).isTrue();
        assertThat(refiner.isRefusal("Control Builder is used like this.")).isFalse();
        assertThat(refiner.isRefusal(null)).isFalse();
    }

    @Test
    void usesAnswerLanguageAndPreservesSupportedContent() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("Svar [1].");
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"), RAG);

        refiner.refine("fraga", sources(), "Utkast [1].", "gemma4:latest", "sv");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).chat(anyString(), prompt.capture(), anyString(), anyDouble(), anyInt(),
                anyBoolean());
        assertThat(prompt.getValue())
                .contains("Always answer in Swedish")
                .contains("smallest necessary change")
                .contains("Do not replace a partly supported answer with a refusal")
                .contains("Keep every statement and citation [n]");
    }

    @Test
    void forwardsRefineThinkingSetting() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("svar");
        OllamaProperties thinking = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "e5", Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b", "qwen3:8b", "qwen3:8b",
                16384, 8192, 2048, 2048, false, true, false, false, false, null);

        new AnswerRefiner(client, thinking, RAG).refine("fraga", sources(), "utkast");

        org.mockito.ArgumentCaptor<Boolean> flag = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(client).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                flag.capture());
        assertThat(flag.getValue()).isTrue();
    }
}
