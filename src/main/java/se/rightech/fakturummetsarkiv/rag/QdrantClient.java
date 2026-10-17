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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        try {
            restClient.get()
                    .uri("/collections/{collection}", properties.collection())
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
        if (collectionExists()) {
            return;
        }
        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", properties.vectorSize(), "distance", properties.distance()));
        restClient.put()
                .uri("/collections/{collection}", properties.collection())
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("Skapade Qdrant-kollektionen '{}'", properties.collection());
    }

    public void upsert(List<Point> points) {
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
                .uri("/collections/{collection}/points?wait=true", properties.collection())
                .body(Map.of("points", payload))
                .retrieve()
                .toBodilessEntity();
    }

    public List<ScoredPoint> search(double[] vector, int limit, Double scoreThreshold) {
        return search(properties.collection(), vector, limit, scoreThreshold);
    }

    public List<ScoredPoint> search(String collection, double[] vector, int limit, Double scoreThreshold) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        if (scoreThreshold != null) {
            body.put("score_threshold", scoreThreshold);
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
