package se.rightech.fakturummetsarkiv.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigurationLoggerTest {

    @Test
    void listsOnlyDistinctConfiguredModels() {
        OllamaProperties properties = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b",
                "qwen3:8b", "qwen3:8b", 8192, 8192, 2048, 2048,
                false, false, false, false, false, 12.0);

        assertThat(ModelConfigurationLogger.configuredModels(properties))
                .containsExactly("qwen3:8b", "qllama/multilingual-e5-large");
    }

    @Test
    void defaultConfigurationHasThreeModels() {
        OllamaProperties properties = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b",
                "phi3.5:latest", "phi3.5:latest", 8192, 8192, 2048, 2048,
                false, false, false, false, false, null);

        assertThat(ModelConfigurationLogger.configuredModels(properties))
                .containsExactly("qwen3:8b", "qllama/multilingual-e5-large", "phi3.5:latest");
    }
}
