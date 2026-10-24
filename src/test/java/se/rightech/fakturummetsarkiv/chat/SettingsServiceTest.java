package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.ArchiveProperties;
import se.rightech.fakturummetsarkiv.rag.HybridProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsServiceTest {

    private final MockEnvironment environment = new MockEnvironment();
    private final EffectiveSettings effective = effective(environment);
    private final SettingsSchema schema = new SettingsSchema();
    private final SettingsService service = new SettingsService(schema, effective,
            new SettingsValidator(schema, effective), environment);

    @Test
    void snapshotContainsGroupsEffectiveValuesAndSources() {
        SettingsService.SettingsSnapshot snapshot = service.snapshot();

        assertThat(snapshot.groups()).extracting(SettingsGroup::id)
                .contains("models", "search", "answer", "validation", "context", "indexing", "limits", "infrastructure");
        assertThat(snapshot.settings()).extracting(SettingsService.SettingView::key)
                .contains("ollama.model", "rag.rerank.enabled", "qdrant.top-k");
        SettingsService.SettingView model = view(snapshot, "ollama.model");
        assertThat(model.value()).isEqualTo("gemma4:latest");
        assertThat(model.source()).isEqualTo("default");
        assertThat(model.applyMode()).isEqualTo(ApplyMode.MODEL_RELOAD);
    }

    @Test
    void updateWritesOverrideAndIncrementsVersion() {
        long version = service.snapshot().version();

        SettingsService.SettingsSnapshot updated = service.update(Map.of(
                "ollama.model", "phi3.5:latest",
                "rag.rerank.enabled", "true",
                "qdrant.top-k", "9"), version);

        assertThat(updated.version()).isGreaterThan(version);
        assertThat(view(updated, "ollama.model").value()).isEqualTo("phi3.5:latest");
        assertThat(view(updated, "ollama.model").source()).isEqualTo("ui");
        assertThat(view(updated, "rag.rerank.enabled").value()).isEqualTo(true);
        assertThat(view(updated, "qdrant.top-k").value()).isEqualTo(9);
    }

    @Test
    void resetRemovesOverride() {
        SettingsService.SettingsSnapshot updated = service.update(Map.of("qdrant.top-k", 9), null);
        long version = updated.version();

        SettingsService.SettingsSnapshot reset = service.reset("qdrant.top-k", version);

        assertThat(view(reset, "qdrant.top-k").value()).isEqualTo(7);
        assertThat(view(reset, "qdrant.top-k").overridden()).isFalse();
    }

    @Test
    void rejectsReadOnlyInvalidAndStaleUpdates() {
        assertThatThrownBy(() -> service.update(Map.of("qdrant.base-url", "http://example"), null))
                .isInstanceOf(ReadOnlySettingException.class);

        assertThatThrownBy(() -> service.update(Map.of("qdrant.top-k", 0), null))
                .isInstanceOf(SettingsValidationException.class)
                .hasMessageContaining("minst 1");

        assertThatThrownBy(() -> service.update(Map.of("chat.answer-language", "de"), null))
                .isInstanceOf(SettingsValidationException.class)
                .hasMessageContaining("en, sv, auto");

        assertThatThrownBy(() -> service.update(Map.of("ollama.model", "x"), 999L))
                .isInstanceOf(StaleSettingsException.class);
    }

    @Test
    void rejectsChunkOverlapThatIsNotSmallerThanChunkSize() {
        assertThatThrownBy(() -> service.update(Map.of("rag.chunk-overlap", 1200), null))
                .isInstanceOf(SettingsValidationException.class)
                .hasMessageContaining("mindre än chunkstorleken");
    }

    @Test
    void rejectsAFourthDistinctModel() {
        assertThatThrownBy(() -> service.update(Map.of("rag.rerank.model", "fourth-model"), null))
                .isInstanceOf(SettingsValidationException.class)
                .hasMessageContaining("Max 3");
    }

    private static SettingsService.SettingView view(SettingsService.SettingsSnapshot snapshot, String key) {
        return snapshot.settings().stream().filter(setting -> setting.key().equals(key)).findFirst().orElseThrow();
    }

    private static EffectiveSettings effective(MockEnvironment environment) {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "phi3.5:latest", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
        return new EffectiveSettings(ollama, qdrant, rag, new ChatProperties(0.1, true, "en"),
                new HybridProperties(false, 50, 60), new RerankProperties(false, 25, "", 600),
                new ArchiveProperties("", "archive"), environment);
    }
}
