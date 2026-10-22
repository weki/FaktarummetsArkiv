package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final QdrantProperties properties;

    @Autowired
    public QdrantClient(QdrantProperties properties) {
        this(properties, createRestClient(properties));
    }

    QdrantClient(QdrantProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient createRestClient(QdrantProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public record Point(String id, double[] vector, Map<String, Object> payload) {
    }

    public record ScoredPoint(String id, double score, Map<String, Object> payload) {
    }

    public boolean collectionExists() {
        return collectionExists(properties.collection());
    }

    public boolean collectionExists(String collection) {
        try {
            restClient.get()
                    .uri("/collections/{collection}", collection)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound ex) {
            return false;
        }
    }

    public List<String> listCollections() {
        JsonNode node = restClient.get()
                .uri("/collections")
                .retrieve()
                .body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (node == null) {
            return names;
        }
        JsonNode collections = node.at("/result/collections");
        if (collections == null || !collections.isArray()) {
            return names;
        }
        for (JsonNode collection : collections) {
            JsonNode name = collection.get("name");
            if (name != null && !name.isNull() && !name.asString().isBlank()) {
                names.add(name.asString());
            }
        }
        return names;
    }

    public void ensureCollection() {
        ensureCollection(properties.collection());
    }

    public void ensureCollection(String collection) {
        if (collectionExists(collection)) {
            return;
        }
        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", properties.vectorSize(), "distance", properties.distance()));
        restClient.put()
                .uri("/collections/{collection}", collection)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("Skapade Qdrant-kollektionen '{}'", collection);
    }

    /** Skapar ett fulltextindex med engelsk tokenizer, stoppord och stemming (idempotent). */
    public void ensureTextIndex(String collection) {
        if (!collectionExists(collection)) {
            return;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "text");
        schema.put("tokenizer", "word");
        schema.put("lowercase", true);
        schema.put("stopwords", Map.of("languages", List.of("english")));
        schema.put("stemmer", Map.of("type", "snowball", "language", "english"));
        restClient.put()
                .uri("/collections/{collection}/index?wait=true", collection)
                .body(Map.of("field_name", "text", "field_schema", schema))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Nyckelordssökning via fulltextindexet. Qdrant saknar poängsättning för filter, så
     * kandidaterna rangordnas lokalt efter antal matchande frågetermer.
     */
    public List<ScoredPoint> keywordSearch(String collection, String query, int limit, List<String> documentIds) {
        List<String> terms = keywordTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("key", "text", "match", Map.of("text", query)));
        List<String> ids = documentIds == null ? List.of() : documentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            must.add(Map.of("key", "documentId", "match", Map.of("any", ids)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", Math.max(1, limit));
        body.put("with_payload", true);
        body.put("with_vector", false);
        body.put("filter", Map.of("must", must));
        JsonNode node;
        try {
            node = restClient.post()
                    .uri("/collections/{collection}/points/scroll", collection)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return List.of();
        }
        List<ScoredPoint> hits = new ArrayList<>();
        if (node == null) {
            return hits;
        }
        JsonNode points = node.at("/result/points");
        if (points == null || !points.isArray()) {
            return hits;
        }
        for (JsonNode point : points) {
            JsonNode payload = point.get("payload");
            Map<String, Object> map = payload == null || payload.isNull() ? Map.of() : toMap(payload);
            String text = String.valueOf(map.getOrDefault("text", "")).toLowerCase(Locale.ROOT);
            long matches = terms.stream().filter(text::contains).count();
            if (matches == 0) {
                continue;
            }
            JsonNode id = point.get("id");
            hits.add(new ScoredPoint(id == null ? null : id.asString(), matches, map));
        }
        hits.sort(Comparator.comparingDouble(ScoredPoint::score).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "will",
            "do", "does", "did", "is", "to", "of", "in", "on", "a", "an", "what", "which", "how",
            "och", "att", "det", "som", "för", "med", "den", "har", "hur", "vad", "jag", "kan",
            "är", "en", "ett", "på", "av", "till", "i");

    static List<String> keywordTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 1 && !STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    /** Hämtar payloaden för första punkten som har metadata om embeddingmodell. */
    public Map<String, Object> firstEmbeddingMetadata(String collection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", 1);
        body.put("with_payload", List.of("embeddingModel", "vectorSize"));
        body.put("with_vector", false);
        body.put("filter", Map.of("must_not", List.of(
                Map.of("is_empty", Map.of("key", "embeddingModel")))));
        try {
            JsonNode node = restClient.post()
                    .uri("/collections/{collection}/points/scroll", collection)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode point = node == null ? null : node.at("/result/points/0/payload");
            return point == null || point.isMissingNode() || point.isNull() ? Map.of() : toMap(point);
        } catch (HttpClientErrorException.NotFound ex) {
            return Map.of();
        }
    }

    public void upsert(List<Point> points) {
        upsert(properties.collection(), points);
    }

    public void upsert(String collection, List<Point> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Point point : points) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", point.id());
            entry.put("vector", point.vector());
            entry.put("payload", point.payload());
            payload.add(entry);
        }
        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collection)
                .body(Map.of("points", payload))
                .retrieve()
                .toBodilessEntity();
    }

    public List<ScoredPoint> search(double[] vector, int limit, Double scoreThreshold) {
        return search(properties.collection(), vector, limit, scoreThreshold, null);
    }

    public List<ScoredPoint> search(String collection, double[] vector, int limit, Double scoreThreshold) {
        return search(collection, vector, limit, scoreThreshold, null);
    }

    public List<ScoredPoint> search(String collection, double[] vector, int limit, Double scoreThreshold,
                                    List<String> documentIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        if (scoreThreshold != null) {
            body.put("score_threshold", scoreThreshold);
        }
        List<String> ids = documentIds == null ? List.of() : documentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            body.put("filter", Map.of("must", List.of(
                    Map.of("key", "documentId", "match", Map.of("any", ids)))));
        }
        JsonNode node = restClient.post()
                .uri("/collections/{collection}/points/search", collection)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<ScoredPoint> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        JsonNode hits = node.get("result");
        if (hits == null || !hits.isArray()) {
            return result;
        }
        for (JsonNode hit : hits) {
            JsonNode id = hit.get("id");
            JsonNode score = hit.get("score");
            JsonNode payload = hit.get("payload");
            result.add(new ScoredPoint(
                    id == null ? null : id.asString(),
                    score == null ? 0.0 : score.asDouble(),
                    payload == null ? Map.of() : toMap(payload)));
        }
        return result;
    }

    public void deleteByDocument(String documentId) {
        deleteByMatch(properties.collection(), "documentId", documentId);
    }

    /** Tar bort alla punkter vars payload-fält {@code key} matchar {@code value}. */
    public void deleteByMatch(String collection, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Map<String, Object> filter = Map.of(
                "must", List.of(Map.of("key", key, "match", Map.of("value", value))));
        restClient.post()
                .uri("/collections/{collection}/points/delete?wait=true", collection)
                .body(Map.of("filter", filter))
                .retrieve()
                .toBodilessEntity();
    }

    /** Hämtar payloaden för alla punkter i kollektionen (endast angivna fält, utan vektorer). */
    public List<Map<String, Object>> scrollPayloads(String collection, List<String> fields) {
        return scrollPayloads(collection, fields, null);
    }

    /** Som {@link #scrollPayloads(String, List)} men filtrerar på ett Qdrant-filter. */
    public List<Map<String, Object>> scrollPayloads(String collection, List<String> fields,
                                                    Map<String, Object> filter) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        Object offset = null;
        try {
            do {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limit", 512);
                body.put("with_payload", fields == null || fields.isEmpty() ? true : fields);
                body.put("with_vector", false);
                if (filter != null && !filter.isEmpty()) {
                    body.put("filter", filter);
                }
                if (offset != null) {
                    body.put("offset", offset);
                }
                JsonNode node = restClient.post()
                        .uri("/collections/{collection}/points/scroll", collection)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                if (node == null) {
                    break;
                }
                JsonNode result = node.get("result");
                if (result == null) {
                    break;
                }
                JsonNode points = result.get("points");
                if (points != null && points.isArray()) {
                    for (JsonNode point : points) {
                        JsonNode payload = point.get("payload");
                        if (payload != null && !payload.isNull()) {
                            payloads.add(toMap(payload));
                        }
                    }
                }
                JsonNode next = result.get("next_page_offset");
                offset = next == null || next.isNull() ? null : JSON.treeToValue(next, Object.class);
            } while (offset != null);
        } catch (HttpClientErrorException.NotFound ex) {
            return List.of();
        }
        return payloads;
    }

    public long count() {
        JsonNode node = restClient.post()
                .uri("/collections/{collection}/points/count", properties.collection())
                .body(Map.of("exact", true))
                .retrieve()
                .body(JsonNode.class);
        if (node == null) {
            return 0;
        }
        JsonNode count = node.at("/result/count");
        return count.isMissingNode() || count.isNull() ? 0 : count.asLong();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        try {
            return JSON.treeToValue(node, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
