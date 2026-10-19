package se.rightech.fakturummetsarkiv.llm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class OllamaClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String KEEP_ALIVE = "1h";

    private final RestClient restClient;

    @Autowired
    public OllamaClient(OllamaProperties properties) {
        this(createRestClient(properties));
    }

    OllamaClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient createRestClient(OllamaProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public String chat(String model, String systemPrompt, String userPrompt) {
        return chat(model, systemPrompt, userPrompt, 0.0);
    }

    public String chat(String model, String systemPrompt, String userPrompt, double temperature) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);
        body.put("options", Map.of("temperature", temperature));
        body.put("keep_alive", KEEP_ALIVE);

        JsonNode node = restClient.post()
                .uri("/api/chat")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (node == null) {
            return "";
        }
        JsonNode content = node.at("/message/content");
        return content.isMissingNode() || content.isNull() ? "" : content.asString();
    }

    public void chatStream(String model, List<Map<String, String>> messages, double temperature,
                           Consumer<String> onContent, Consumer<String> onThought) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("messages", messages);
        body.put("options", Map.of("temperature", temperature));
        body.put("keep_alive", KEEP_ALIVE);

        restClient.post()
                .uri("/api/chat")
                .body(body)
                .exchange((clientRequest, clientResponse) -> {
                    InputStream stream = clientResponse.getBody();
                    if (stream == null) {
                        return null;
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            try {
                                JsonNode node = JSON.readTree(line);
                                JsonNode content = node.at("/message/content");
                                if (!content.isMissingNode() && !content.isNull()) {
                                    String token = content.asString();
                                    if (!token.isEmpty() && onContent != null) {
                                        onContent.accept(token);
                                    }
                                }
                                JsonNode thinking = node.at("/message/thinking");
                                if (!thinking.isMissingNode() && !thinking.isNull()) {
                                    String token = thinking.asString();
                                    if (!token.isEmpty() && onThought != null) {
                                        onThought.accept(token);
                                    }
                                }
                            } catch (Exception ignored) {
                                // skip malformed lines
                            }
                        }
                    }
                    return null;
                });
    }

    public void chatStream(String model, List<Map<String, String>> messages, double temperature,
                           Consumer<String> onToken) {
        chatStream(model, messages, temperature, onToken, null);
    }

    public List<double[]> embed(String model, List<String> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("keep_alive", KEEP_ALIVE);

        JsonNode node = restClient.post()
                .uri("/api/embed")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<double[]> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        JsonNode embeddings = node.get("embeddings");
        if (embeddings == null || !embeddings.isArray()) {
            return result;
        }
        for (JsonNode embedding : embeddings) {
            if (!embedding.isArray()) {
                continue;
            }
            double[] vector = new double[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).asDouble();
            }
            result.add(vector);
        }
        return result;
    }

    public double[] embedOne(String model, String input) {
        List<double[]> vectors = embed(model, List.of(input == null ? "" : input));
        return vectors.isEmpty() ? new double[0] : vectors.get(0);
    }

    public List<String> listModels() {
        JsonNode node = restClient.get()
                .uri("/api/tags")
                .retrieve()
                .body(JsonNode.class);
        List<String> models = new ArrayList<>();
        if (node == null) {
            return models;
        }
        JsonNode entries = node.get("models");
        if (entries == null || !entries.isArray()) {
            return models;
        }
        for (JsonNode entry : entries) {
            JsonNode name = entry.get("name");
            if (name != null && !name.isNull() && !name.asString().isBlank()) {
                models.add(name.asString());
            }
        }
        return models;
    }
}
