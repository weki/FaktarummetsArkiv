package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsStoreTest {

    private static SettingsStore store() {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "phi3.5:latest", "", 16384, 4096);
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en");
        return new SettingsStore(qdrant, ollama, rag);
    }

    @Test
    void exposesDefaultsFromConfiguration() {
        WorkspaceSettings settings = store().get();

        assertThat(settings.topN()).isEqualTo(7);
        assertThat(settings.chatMode()).isEqualTo("chat");
        assertThat(settings.chatModel()).isEqualTo("gemma4:latest");
        assertThat(settings.validatorModel()).isEqualTo("phi3.5:latest");
        assertThat(settings.validateGrounding()).isTrue();
        assertThat(settings.smartSearch()).isTrue();
        assertThat(settings.rewriteQuery()).isTrue();
        assertThat(settings.collections()).containsExactly("docs");
        assertThat(settings.historySize()).isEqualTo(20);
    }

    @Test
    void mergesPartialUpdates() {
        SettingsStore store = store();

        WorkspaceSettings updated = store.update(new WorkspaceSettings(
                "query", null, null, null, null, null, List.of("docs", "manuals"), 0.3, 12, 0.5, null, null));

        assertThat(updated.chatMode()).isEqualTo("query");
        assertThat(updated.similarityThreshold()).isEqualTo(0.3);
        assertThat(updated.topN()).isEqualTo(12);
        assertThat(updated.temperature()).isEqualTo(0.5);
        assertThat(updated.chatModel()).isEqualTo("gemma4:latest");
        assertThat(updated.validatorModel()).isEqualTo("phi3.5:latest");
        assertThat(updated.collections()).containsExactly("docs", "manuals");
        assertThat(store.get().topN()).isEqualTo(12);
    }

    @Test
    void rejectsMoreThanThreeDistinctModels() {
        SettingsStore store = store();

        assertThatThrownBy(() -> store.update(new WorkspaceSettings(
                "chat", "model-a", "model-b", true, true, true, List.of("docs"), null, 5, 0.0, 20, null,
                "model-c", "model-d", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max 3");
    }

    @Test
    void rejectsOutOfRangeValues() {
        SettingsStore store = store();

        assertThatThrownBy(() -> store.update(new WorkspaceSettings(
                null, null, null, null, null, null, null, 1.5, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.update(new WorkspaceSettings(
                null, null, null, null, null, null, null, null, 0, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.update(new WorkspaceSettings(
                null, null, null, null, null, null, null, null, null, 3.0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.update(new WorkspaceSettings(
                null, null, null, null, null, null, null, null, null, null, 200, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
