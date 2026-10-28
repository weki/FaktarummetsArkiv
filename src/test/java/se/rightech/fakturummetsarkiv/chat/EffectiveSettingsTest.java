package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.HybridProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveSettingsTest {

    private static EffectiveSettings inherited() {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "", true, "",
                "", "", 16384, 0, 0, 0, false, false, false, false, false, null);
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
        return new EffectiveSettings(ollama, qdrant, rag, new ChatProperties(0.1, true, "en"),
                new HybridProperties(false, 50, 60), new RerankProperties(false, 25, "", 600));
    }

    private static EffectiveSettings effective() {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "phi3.5:latest", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
        return new EffectiveSettings(ollama, qdrant, rag, new ChatProperties(0.1, true, "en"),
                new HybridProperties(false, 50, 60), new RerankProperties(false, 25, "", 600));
    }

    @Test
    void overrideWinsUntilRemoved() {
        EffectiveSettings settings = effective();

        settings.putOverride("ollama.model", "override-model");

        assertThat(settings.string("ollama.model")).isEqualTo("override-model");
        assertThat(settings.string("ollama.validator-model")).isEqualTo("phi3.5:latest");

        settings.removeOverride("ollama.model");

        assertThat(settings.string("ollama.model")).isEqualTo("gemma4:latest");
    }

    @Test
    void blankRoleModelInheritsChatModel() {
        EffectiveSettings settings = inherited();

        assertThat(settings.effectiveModel("chat")).isEqualTo("gemma4:latest");
        assertThat(settings.effectiveModel("validator")).isEqualTo("gemma4:latest");
        assertThat(settings.effectiveModel("refine")).isEqualTo("gemma4:latest");
        assertThat(settings.effectiveModel("gate")).isEqualTo("gemma4:latest");
        assertThat(settings.effectiveModel("rewrite")).isEqualTo("gemma4:latest");
        assertThat(settings.effectiveModel("followup")).isEqualTo("gemma4:latest");
        assertThat(settings.effectiveModel("rerank")).isEqualTo("gemma4:latest");
        assertThat(settings.hasOwnModel("gate")).isFalse();
    }

    @Test
    void ownRoleModelWinsOverInheritance() {
        EffectiveSettings settings = inherited();
        settings.putOverride("ollama.gate-model", "phi3.5:latest");

        assertThat(settings.effectiveModel("gate")).isEqualTo("phi3.5:latest");
        assertThat(settings.effectiveModel("validator")).isEqualTo("gemma4:latest");
        assertThat(settings.hasOwnModel("gate")).isTrue();
    }

    @Test
    void changingChatModelFlowsToInheritingRoles() {
        EffectiveSettings settings = inherited();
        settings.putOverride("ollama.model", "qwen3:8b");

        assertThat(settings.effectiveModel("validator")).isEqualTo("qwen3:8b");
        assertThat(settings.effectiveModel("refine")).isEqualTo("qwen3:8b");
        assertThat(settings.effectiveModel("gate")).isEqualTo("qwen3:8b");
        assertThat(settings.effectiveModel("rewrite")).isEqualTo("qwen3:8b");
        assertThat(settings.effectiveModel("followup")).isEqualTo("qwen3:8b");
        assertThat(settings.effectiveModel("rerank")).isEqualTo("qwen3:8b");
    }

    @Test
    void numCtxIsInheritedUnlessRoleHasOwnValue() {
        EffectiveSettings settings = inherited();

        assertThat(settings.effectiveNumCtx("chat")).isEqualTo(16384);
        assertThat(settings.effectiveNumCtx("validator")).isEqualTo(16384);
        assertThat(settings.effectiveNumCtx("gate")).isEqualTo(16384);
        assertThat(settings.effectiveNumCtx("rewrite")).isEqualTo(16384);
        assertThat(settings.effectiveNumCtx("refine")).isEqualTo(16384);
        assertThat(settings.effectiveNumCtx("followup")).isEqualTo(16384);
        assertThat(settings.effectiveNumCtx("rerank")).isEqualTo(16384);

        settings.putOverride("ollama.gate-num-ctx", 4096);

        assertThat(settings.effectiveNumCtx("gate")).isEqualTo(4096);
        assertThat(settings.effectiveNumCtx("validator")).isEqualTo(16384);
        assertThat(settings.hasOwnNumCtx("gate")).isTrue();
    }

    @Test
    void savedPerRoleValueIsAnOwnOverride() {
        EffectiveSettings settings = effective();

        assertThat(settings.effectiveModel("gate")).isEqualTo("phi3.5:latest");
        assertThat(settings.effectiveModel("validator")).isEqualTo("phi3.5:latest");
        assertThat(settings.effectiveNumCtx("validator")).isEqualTo(4096);
        assertThat(settings.hasOwnModel("gate")).isTrue();
        assertThat(settings.hasOwnNumCtx("validator")).isTrue();
    }

    @Test
    void workspaceReflectsOverridesPerRead() {
        SettingsStore store = new SettingsStore(
                new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                        Duration.ofSeconds(5), "query: ", "passage:"), effective());

        assertThat(store.get().chatMode()).isEqualTo("chat");
        store.update(new WorkspaceSettings("query", null, null, null, null, null,
                List.of("docs"), null, 12, 0.4, 10, null, null, null, null, null, "sv"));

        WorkspaceSettings current = store.get();
        assertThat(current.chatMode()).isEqualTo("query");
        assertThat(current.topN()).isEqualTo(12);
        assertThat(current.temperature()).isEqualTo(0.4);
        assertThat(current.historySize()).isEqualTo(10);
        assertThat(current.answerLanguage()).isEqualTo("sv");
    }
}
