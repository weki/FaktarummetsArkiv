package se.rightech.fakturummetsarkiv.rag;

import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalService {

    private final OllamaClient llm;
    private final OllamaProperties ollama;
    private final QdrantProperties qdrant;
    private final QdrantClient qdrantClient;
    private final SettingsStore settingsStore;
    private final HybridProperties hybrid;
    private final Reranker reranker;
    private final RerankProperties rerank;

    public RetrievalService(OllamaClient llm, OllamaProperties ollama, QdrantProperties qdrant,
                            QdrantClient qdrantClient, SettingsStore settingsStore, HybridProperties hybrid,
                            Reranker reranker, RerankProperties rerank) {
        this.llm = llm;
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.qdrantClient = qdrantClient;
        this.settingsStore = settingsStore;
        this.hybrid = hybrid;
        this.reranker = reranker;
        this.rerank = rerank;
    }

    public List<Map<String, Object>> retrieve(String query, List<String> collections, int topK, Double scoreThreshold) {
        return retrieve(query, collections, topK, scoreThreshold, null);
    }

    public List<Map<String, Object>> retrieve(String query, List<String> collections, int topK, Double scoreThreshold,
                                          List<String> documentIds) {
        String model = settingsStore.get().embeddingModel();
        if (model == null || model.isBlank()) {
            model = ollama.embeddingModel();
        }
        if (model == null || model.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        double[] vector = llm.embedOne(model, qdrant.queryInput(model, query));
        if (vector.length == 0) {
            return List.of();
        }

        List<String> targets = resolveCollections(collections);
        if (targets.contains(qdrant.collection())) {
            qdrantClient.ensureCollection();
        }

        List<Map<String, Object>> sources;
        if (hybrid.enabled()) {
            sources = hybridSearch(query, vector, targets, topK, scoreThreshold, documentIds);
        } else {
            sources = denseSearch(vector, targets, topK, scoreThreshold, documentIds);
        }

        List<Map<String, Object>> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> source : sources) {
            String key = dedupKey(source);
            if (seen.add(key)) {
                deduped.add(source);
            }
        }
        if (rerank.enabled() && deduped.size() > 1) {
            int candidates = Math.max(topK, rerank.candidates());
            List<Map<String, Object>> shortlist = deduped.size() > candidates
                    ? deduped.subList(0, candidates) : deduped;
            List<Map<String, Object>> reranked = reranker.rerank(query, shortlist, topK);
            if (reranked != null && !reranked.isEmpty()) {
                return reranked.size() > topK ? reranked.subList(0, topK) : reranked;
            }
        }
        return deduped.size() > topK ? deduped.subList(0, topK) : deduped;
    }

    private List<Map<String, Object>> denseSearch(double[] vector, List<String> targets, int topK,
                                                  Double scoreThreshold, List<String> documentIds) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String collection : targets) {
            for (QdrantClient.ScoredPoint hit : qdrantClient.search(collection, vector, topK, scoreThreshold,
                    documentIds)) {
                sources.add(toSource(collection, hit.payload(), hit.score()));
            }
        }
        sources.sort(Comparator.comparingDouble((Map<String, Object> source) ->
                ((Number) source.get("score")).doubleValue()).reversed());
        return sources;
    }

    private List<Map<String, Object>> hybridSearch(String query, double[] vector, List<String> targets, int topK,
                                                   Double scoreThreshold, List<String> documentIds) {
        int candidates = Math.max(topK * 4, 20);
        Map<String, FusedHit> fused = new LinkedHashMap<>();

        for (String collection : targets) {
            qdrantClient.ensureTextIndex(collection);
            List<QdrantClient.ScoredPoint> dense = qdrantClient.search(collection, vector, candidates,
                    scoreThreshold, documentIds);
            for (int i = 0; i < dense.size(); i++) {
                QdrantClient.ScoredPoint hit = dense.get(i);
                addFused(fused, hit, collection, 1.0 / (hybrid.rrfK() + i + 1));
            }
            List<QdrantClient.ScoredPoint> keyword = qdrantClient.keywordSearch(collection, query,
                    hybrid.keywordLimit(), documentIds);
            for (int i = 0; i < keyword.size(); i++) {
                QdrantClient.ScoredPoint hit = keyword.get(i);
                addFused(fused, hit, collection, 1.0 / (hybrid.rrfK() + i + 1));
            }
        }

        List<FusedHit> ranked = new ArrayList<>(fused.values());
        ranked.sort(Comparator.comparingDouble(FusedHit::rrf).reversed());

        List<Map<String, Object>> sources = new ArrayList<>();
        for (FusedHit hit : ranked) {
            Map<String, Object> source = toSource(hit.collection(), hit.payload(), hit.rawScore());
            source.put("score", hit.rrf());
            sources.add(source);
        }
        return sources;
    }

    private static void addFused(Map<String, FusedHit> fused, QdrantClient.ScoredPoint hit,
                                 String collection, double contribution) {
        if (hit == null || hit.id() == null) {
            return;
        }
        FusedHit current = fused.get(hit.id());
        if (current == null) {
            fused.put(hit.id(), new FusedHit(collection, hit.payload(), hit.score(), contribution));
        } else {
            current.add(contribution);
        }
    }

    private static Map<String, Object> toSource(String collection, Map<String, Object> payload, double score) {
        Map<String, Object> source = new LinkedHashMap<>();
        Object title = payload.get("title");
        if (title == null) {
            title = payload.get("source");
        }
        source.put("title", title == null ? "Källa" : title);
        source.put("text", payload.getOrDefault("text", ""));
        source.put("score", score);
        source.put("collection", collection);
        copy(payload, source, "documentId");
        copy(payload, source, "source");
        copy(payload, source, "page");
        copy(payload, source, "chunk");
        copy(payload, source, "chunkIndex");
        return source;
    }

    private static final class FusedHit {
        private final String collection;
        private final Map<String, Object> payload;
        private final double rawScore;
        private double rrf;

        private FusedHit(String collection, Map<String, Object> payload, double rawScore, double rrf) {
            this.collection = collection;
            this.payload = payload;
            this.rawScore = rawScore;
            this.rrf = rrf;
        }

        void add(double contribution) {
            rrf += contribution;
        }

        String collection() {
            return collection;
        }

        Map<String, Object> payload() {
            return payload;
        }

        double rawScore() {
            return rawScore;
        }

        double rrf() {
            return rrf;
        }
    }

    private static void copy(Map<String, Object> payload, Map<String, Object> target, String key) {
        if (payload.containsKey(key) && payload.get(key) != null) {
            target.put(key, payload.get(key));
        }
    }

    private List<String> resolveCollections(List<String> collections) {
        List<String> result = new ArrayList<>();
        if (collections != null) {
            for (String collection : collections) {
                if (collection != null && !collection.isBlank() && !result.contains(collection)) {
                    result.add(collection);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(qdrant.collection());
        }
        return result;
    }

    private static String dedupKey(Map<String, Object> source) {
        String path = strOrNull(source.get("path"));
        String src = strOrNull(source.get("source"));
        String page = strOrNull(source.get("page"));
        String chunk = strOrNull(source.get("chunk"));
        if (chunk == null) {
            chunk = strOrNull(source.get("chunkIndex"));
        }
        if (path != null && page != null && chunk != null) {
            return "p:" + path + "|pg:" + page + "|c:" + chunk;
        }
        if (src != null && page != null && chunk != null) {
            return "s:" + src + "|pg:" + page + "|c:" + chunk;
        }
        if (path != null) {
            return "path:" + path;
        }
        if (src != null) {
            return "src:" + src;
        }
        Object textObj = source.get("text");
        String text = textObj == null ? "" : String.valueOf(textObj);
        return "txt:" + text.length() + ":" + text.hashCode();
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }
}
