package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.chat.WorkspaceSettings;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private final OllamaClient llm = mock(OllamaClient.class);
    private final QdrantClient qdrantClient = mock(QdrantClient.class);
    private final SettingsStore settingsStore = mock(SettingsStore.class);
    private final Reranker reranker = mock(Reranker.class);

    private static final OllamaProperties OLLAMA = new OllamaProperties(
            "http://localhost:11434", "qwen3:8b", "qllama/multilingual-e5-large",
            Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b", "phi3.5:latest", "", 16384, 4096);
    private static final QdrantProperties QDRANT = new QdrantProperties(
            "http://localhost:6333", "docs", 1024, "Cosine", 5,
            Duration.ofSeconds(5), "query: ", "passage: ");

    private RetrievalService service(boolean hybridEnabled) {
        return service(hybridEnabled, false);
    }

    private RetrievalService service(boolean hybridEnabled, boolean rerankEnabled) {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "qwen3:8b", "qwen3:8b", true, true, true,
                List.of("docs"), 0.3, 5, 0.0, 20, null));
        when(llm.embedOne(anyString(), anyString())).thenReturn(new double[]{0.1, 0.2});
        return new RetrievalService(llm, OLLAMA, QDRANT, qdrantClient, settingsStore,
                new HybridProperties(hybridEnabled, 50, 60), reranker,
                new RerankProperties(rerankEnabled, 25, "", 600));
    }

    private static QdrantClient.ScoredPoint hit(String id, double score, String text) {
        return new QdrantClient.ScoredPoint(id, score,
                Map.of("title", "doc-" + id, "source", "doc-" + id, "text", text, "chunkIndex", 0));
    }

    @Test
    void fusesVectorAndKeywordResultsWithRrf() {
        RetrievalService service = service(true);
        when(qdrantClient.search(eq("docs"), any(double[].class), anyInt(), any(), any()))
                .thenReturn(List.of(hit("a", 0.9, "alpha"), hit("b", 0.8, "beta")));
        when(qdrantClient.keywordSearch(eq("docs"), anyString(), anyInt(), any()))
                .thenReturn(List.of(hit("b", 2.0, "beta"), hit("c", 1.0, "gamma")));

        List<Map<String, Object>> sources = service.retrieve("beta", List.of("docs"), 5, 0.3, null);

        assertThat(sources).extracting(source -> source.get("documentId"))
                .containsExactly(null, null, null);
        assertThat(sources).extracting(source -> source.get("title"))
                .containsExactly("doc-b", "doc-a", "doc-c");
        verify(qdrantClient).ensureTextIndex("docs");
    }

    @Test
    void reranksCandidatesBeforeApplyingTopK() {
        RetrievalService service = service(false, true);
        List<Map<String, Object>> candidates = List.of(
                source("a", "alpha"), source("b", "beta"), source("c", "gamma"));
        when(qdrantClient.search(eq("docs"), any(double[].class), anyInt(), any(), any()))
                .thenReturn(List.of(hit("a", 0.9, "alpha"), hit("b", 0.8, "beta"), hit("c", 0.7, "gamma")));
        when(reranker.rerank(eq("beta"), any(), eq(2))).thenReturn(List.of(candidates.get(1), candidates.get(2)));

        List<Map<String, Object>> sources = service.retrieve("beta", List.of("docs"), 2, 0.3, null);

        assertThat(sources).extracting(source -> source.get("title"))
                .containsExactly("doc-b", "doc-c");
    }

    private static Map<String, Object> source(String id, String text) {
        return Map.of("title", "doc-" + id, "text", text);
    }

    @Test
    void usesOnlyVectorSearchWhenHybridIsDisabled() {
        RetrievalService service = service(false);
        when(qdrantClient.search(eq("docs"), any(double[].class), anyInt(), any(), any()))
                .thenReturn(List.of(hit("a", 0.9, "alpha"), hit("b", 0.8, "beta")));

        List<Map<String, Object>> sources = service.retrieve("beta", List.of("docs"), 5, 0.3, null);

        assertThat(sources).extracting(source -> source.get("title"))
                .containsExactly("doc-a", "doc-b");
        verify(qdrantClient, never()).keywordSearch(anyString(), anyString(), anyInt(), any());
        verify(qdrantClient, never()).ensureTextIndex(anyString());
    }
}
