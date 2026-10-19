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
        return retrieve(query, collections, topK, scoreThreshold, null);
    }

    public List<Map<String, Object>> retrieve(String query, List<String> collections, int topK, Double scoreThreshold,
                                          List<String> documentIds) {
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

        java.util.Set<String> allowed = (documentIds == null || documentIds.isEmpty())
                ? null : new java.util.HashSet<>(documentIds);

        List<Map<String, Object>> sources = new ArrayList<>();
        for (String collection : targets) {
            for (QdrantClient.ScoredPoint hit : qdrantClient.search(collection, vector, topK, scoreThreshold)) {
                Map<String, Object> payload = hit.payload();
                String docId = payload.get("documentId") == null ? null : String.valueOf(payload.get("documentId"));
                if (allowed != null && (docId == null || !allowed.contains(docId))) {
                    continue;
                }
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
                copy(payload, source, "chunkIndex");
                sources.add(source);
            }
        }

        sources.sort(Comparator.comparingDouble((Map<String, Object> source) ->
                ((Number) source.get("score")).doubleValue()).reversed());

        List<Map<String, Object>> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> source : sources) {
            String key = dedupKey(source);
            if (seen.add(key)) {
                deduped.add(source);
                if (deduped.size() >= topK) {
                    break;
                }
            }
        }
        return deduped;
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
