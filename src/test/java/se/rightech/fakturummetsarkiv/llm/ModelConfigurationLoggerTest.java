package se.rightech.fakturummetsarkiv.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import se.rightech.fakturummetsarkiv.chat.ChatProperties;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;
import se.rightech.fakturummetsarkiv.rag.HybridProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ModelConfigurationLoggerTest {

    @Test
    void logsEffectiveRoleAndInheritance(CapturedOutput output) {
        EffectiveSettings effective = effective();
        new ModelConfigurationLogger(properties(), new RerankProperties(false, 25, "", 600), effective)
                .run(null);

        assertThat(output).contains("Rollkonfiguration gate: model=qwen3:8b (ärvd) num_ctx=16384 (ärvd)");
        assertThat(output).contains("Rollkonfiguration chat: model=qwen3:8b (egen) num_ctx=16384 (egen)");
    }

    @Test
    void warnsWhenSameModelUsesDifferentNumCtx(CapturedOutput output) {
        EffectiveSettings effective = effective();
        effective.putOverride("ollama.gate-num-ctx", 4096);

        new ModelConfigurationLogger(properties(), new RerankProperties(false, 25, "", 600), effective)
                .run(null);

        assertThat(output).contains("Modell qwen3:8b används med olika num_ctx");
        assertThat(output).contains("Ollama laddar om modellen mellan anropen");
    }

    private static OllamaProperties properties() {
        return new OllamaProperties("http://localhost:11434", "qwen3:8b", "e5", Duration.ofSeconds(5),
                "", true, "", "", "", 16384, 0, 0, 0, false, false, false, false, false, null);
    }

    private static EffectiveSettings effective() {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
        return new EffectiveSettings(properties(), qdrant, rag, new ChatProperties(0.1, true, "en"),
                new HybridProperties(false, 50, 60), new RerankProperties(false, 25, "", 600));
    }
}
