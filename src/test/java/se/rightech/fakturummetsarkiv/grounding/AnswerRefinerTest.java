package se.rightech.fakturummetsarkiv.grounding;

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

class AnswerRefinerTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String refineModel) {
        return new OllamaProperties("http://localhost:11434", "deepseek-r1:14b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, refineModel, "");
    }

    private static List<Map<String, Object>> sources() {
        return List.of(Map.of("title", "dok", "text", "innehall"));
    }

    @Test
    void refinesUsingConfiguredModel() {
        when(client.chat(eq("gemma4:latest"), any(), any())).thenReturn("  grundat svar  ");
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"));

        assertThat(refiner.enabled()).isTrue();
        assertThat(refiner.refine("fraga", sources(), "utkast")).isEqualTo("grundat svar");
    }

    @Test
    void disabledWithoutModel() {
        AnswerRefiner refiner = new AnswerRefiner(client, props(""));

        assertThat(refiner.enabled()).isFalse();
        assertThat(refiner.refine("fraga", sources(), "utkast")).isNull();
    }

    @Test
    void returnsNullWithoutSources() {
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"));

        assertThat(refiner.refine("fraga", List.of(), "utkast")).isNull();
    }

    @Test
    void returnsNullOnError() {
        when(client.chat(any(), any(), any())).thenThrow(new RuntimeException("nere"));
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"));

        assertThat(refiner.refine("fraga", sources(), "utkast")).isNull();
    }

    @Test
    void detectsRefusal() {
        AnswerRefiner refiner = new AnswerRefiner(client, props("gemma4:latest"));

        assertThat(refiner.isRefusal("Jag hittar inte den informationen i dokumenten.")).isTrue();
        assertThat(refiner.isRefusal("Control Builder används så här.")).isFalse();
        assertThat(refiner.isRefusal(null)).isFalse();
    }
}
