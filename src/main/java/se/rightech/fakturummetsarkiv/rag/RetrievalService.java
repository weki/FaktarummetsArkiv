package se.rightech.fakturummetsarkiv.rag;

import org.springframework.stereotype.Service;
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

    public RetrievalService(OllamaClient llm, OllamaProperties ollama, QdrantProperties qdrant,
                            QdrantClient qdrantClient) {
        this.llm = llm;
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.qdrantClient = qdrantClient;
    }

    public List<Map<String, Object>> retrieve(String query, List<String> collections, int topK, Double scoreThreshold) {
        String model = ollama.embeddingModel();
        if (model == null || model.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        double[] vector = llm.embedOne(model, qdrant.queryPrefix() + query);
        if (vector.length == 0) {
            return List.of();
        }

        List<String> targets = resolveCollections(collections);
        if (targets.contains(qdrant.collection())) {
            qdrantClient.ensureCollection();
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        for (String collection : targets) {
            for (QdrantClient.ScoredPoint hit : qdrantClient.search(collection, vector, topK, scoreThreshold)) {
                Map<String, Object> payload = hit.payload();
                Map<String, Object> source = new LinkedHashMap<>();
                Object title = payload.get("title");
                if (title == null) {
                    title = payload.get("source");
                }
                source.put("title", title == null ? "Källa" : title);
                source.put("text", payload.getOrDefault("text", ""));
                source.put("score", hit.score());
                source.put("collection", collection);
                copy(payload, source, "documentId");
                copy(payload, source, "source");
                copy(payload, source, "path");
                copy(payload, source, "page");
                copy(payload, source, "chunk");
                sources.add(source);
            }
        }

        sources.sort(Comparator.comparingDouble((Map<String, Object> source) ->
                ((Number) source.get("score")).doubleValue()).reversed());
        return sources.size() > topK ? new ArrayList<>(sources.subList(0, topK)) : sources;
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
}
