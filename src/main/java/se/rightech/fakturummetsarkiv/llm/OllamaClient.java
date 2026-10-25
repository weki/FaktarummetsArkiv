package se.rightech.fakturummetsarkiv.llm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import se.rightech.fakturummetsarkiv.chat.ThinkTagFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class OllamaClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String KEEP_ALIVE = "1h";
    private static final int DEFAULT_NUM_CTX = 16384;

    private static final Duration RESET_TIMEOUT = Duration.ofSeconds(15);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ollama-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final RestClient restClient;
    private final int chatNumCtx;
    private final Duration idleTimeout;
    private final Set<ActiveCall> activeCalls = ConcurrentHashMap.newKeySet();
    private final Set<Scope> openScopes = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Scope> CURRENT_SCOPE = new ThreadLocal<>();

    @Autowired
    public OllamaClient(OllamaProperties properties) {
        this(createRestClient(properties), properties.chatNumCtx(), properties.timeout());
    }

    OllamaClient(RestClient restClient) {
        this(restClient, DEFAULT_NUM_CTX, null);
    }

    OllamaClient(RestClient restClient, int chatNumCtx) {
        this(restClient, chatNumCtx, null);
    }

    OllamaClient(RestClient restClient, int chatNumCtx, Duration idleTimeout) {
        this.restClient = restClient;
        this.chatNumCtx = chatNumCtx > 0 ? chatNumCtx : DEFAULT_NUM_CTX;
        this.idleTimeout = idleTimeout;
    }

    // The JDK client aborts on thread interrupt, both while waiting for headers and while
    // reading the body, which is what lets reset() and the idle watchdog cancel a hung call.
    // Its own read timeout covers the whole response, so streams use the watchdog instead.
    private static RestClient createRestClient(OllamaProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeout())
                .build();
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /**
     * Groups the Ollama calls made for one chat request. Once cancelled, every later call in the
     * scope fails immediately, so fallbacks in the pipeline cannot start new generations after a
     * reset or after the browser has gone away.
     */
    public static final class Scope {
        private volatile String cancelReason;

        public boolean isCancelled() {
            return cancelReason != null;
        }
    }

    public Scope openScope() {
        Scope scope = new Scope();
        openScopes.add(scope);
        return scope;
    }

    public void closeScope(Scope scope) {
        openScopes.remove(scope);
    }

    public void cancelScope(Scope scope, String reason) {
        if (scope.cancelReason == null) {
            scope.cancelReason = reason;
        }
        for (ActiveCall call : List.copyOf(activeCalls)) {
            if (call.scope == scope) {
                call.cancel(reason);
            }
        }
    }

    public Runnable within(Scope scope, Runnable task) {
        return () -> within(scope, () -> {
            task.run();
            return null;
        }).get();
    }

    public <T> Supplier<T> within(Scope scope, Supplier<T> task) {
        return () -> {
            Scope previous = CURRENT_SCOPE.get();
            CURRENT_SCOPE.set(scope);
            try {
                return task.get();
            } finally {
                if (previous == null) {
                    CURRENT_SCOPE.remove();
                } else {
                    CURRENT_SCOPE.set(previous);
                }
            }
        };
    }

    private static final class ActiveCall {
        private final Thread thread = Thread.currentThread();
        private final Scope scope = CURRENT_SCOPE.get();
        private volatile long lastActivity = System.nanoTime();
        private boolean done;
        private String cancelReason;

        void touch() {
            lastActivity = System.nanoTime();
        }

        synchronized boolean cancel(String reason) {
            if (done || cancelReason != null) {
                return false;
            }
            cancelReason = reason;
            thread.interrupt();
            return true;
        }

        void cancelIfIdle(Duration timeout) {
            if (System.nanoTime() - lastActivity > timeout.toNanos()) {
                cancel("Ollama svarade inte inom " + timeout.toSeconds() + " s");
            }
        }

        synchronized String finish() {
            done = true;
            // Clear an interrupt we delivered so it does not leak into the pooled thread's next task.
            if (cancelReason != null) {
                Thread.interrupted();
            }
            return cancelReason;
        }
    }

    private <T> T call(Duration timeout, Function<ActiveCall, T> body) {
        ActiveCall call = new ActiveCall();
        activeCalls.add(call);
        // Checked after registering so a concurrent reset either sees this call or we see its flag.
        if (call.scope != null && call.scope.cancelReason != null) {
            activeCalls.remove(call);
            throw new ResourceAccessException(call.scope.cancelReason);
        }
        ScheduledFuture<?> watchdog = null;
        if (timeout != null) {
            long checkMs = Math.max(200, Math.min(1000, timeout.toMillis() / 4));
            watchdog = WATCHDOG.scheduleWithFixedDelay(() -> call.cancelIfIdle(timeout),
                    checkMs, checkMs, TimeUnit.MILLISECONDS);
        }
        try {
            return body.apply(call);
        } catch (RestClientException ex) {
            String reason = call.finish();
            throw reason != null ? new ResourceAccessException(reason) : ex;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            activeCalls.remove(call);
            call.finish();
        }
    }

    private <T> T call(Supplier<T> body) {
        return call(idleTimeout, ignored -> body.get());
    }

    /** Cancels every in-flight request to Ollama and returns how many were cancelled. */
    public int cancelActiveCalls() {
        for (Scope scope : List.copyOf(openScopes)) {
            if (scope.cancelReason == null) {
                scope.cancelReason = "Avbrutet: Ollama återställdes";
            }
        }
        int cancelled = 0;
        for (ActiveCall call : List.copyOf(activeCalls)) {
            if (call.cancel("Avbrutet: Ollama återställdes")) {
                cancelled++;
            }
        }
        return cancelled;
    }

    public record ResetResult(int cancelledRequests, List<String> unloadedModels, List<String> failedModels) {
    }

    /**
     * Cancels in-flight requests and unloads every model Ollama has in memory, so the next
     * request starts from a freshly loaded model. Does not restart the Ollama process itself.
     */
    public ResetResult reset() {
        int cancelled = cancelActiveCalls();
        List<String> loaded = call(RESET_TIMEOUT, ignored -> runningModels());
        List<String> unloaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String model : loaded) {
            if (unload(model)) {
                unloaded.add(model);
            } else {
                failed.add(model);
            }
        }
        return new ResetResult(cancelled, unloaded, failed);
    }

    private List<String> runningModels() {
        JsonNode node = restClient.get()
                .uri("/api/ps")
                .retrieve()
                .body(JsonNode.class);
        List<String> models = new ArrayList<>();
        JsonNode entries = node == null ? null : node.get("models");
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

    private boolean unload(String model) {
        // Embedding-only models reject /api/generate, so fall back to /api/embed.
        for (String uri : List.of("/api/generate", "/api/embed")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            if (uri.equals("/api/embed")) {
                body.put("input", "");
            } else {
                body.put("stream", false);
            }
            body.put("keep_alive", 0);
            try {
                call(RESET_TIMEOUT, ignored -> restClient.post().uri(uri).body(body).retrieve().toBodilessEntity());
                return true;
            } catch (RestClientException ex) {
                // try the next endpoint
            }
        }
        return false;
    }

    public String chat(String model, String systemPrompt, String userPrompt) {
        return chat(model, systemPrompt, userPrompt, 0.0, chatNumCtx, false);
    }

    public String chat(String model, String systemPrompt, String userPrompt, double temperature) {
        return chat(model, systemPrompt, userPrompt, temperature, chatNumCtx, false);
    }

    public String chat(String model, String systemPrompt, String userPrompt, double temperature, int numCtx) {
        return chat(model, systemPrompt, userPrompt, temperature, numCtx, false);
    }

    public String chat(String model, String systemPrompt, String userPrompt, double temperature, int numCtx,
                       boolean thinking) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);
        body.put("options", Map.of("temperature", temperature, "num_ctx", numCtx));
        body.put("think", thinking);
        body.put("keep_alive", KEEP_ALIVE);

        JsonNode node = call(() -> restClient.post()
                .uri("/api/chat")
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        if (node == null) {
            return "";
        }
        JsonNode content = node.at("/message/content");
        return content.isMissingNode() || content.isNull() ? "" : ThinkTagFilter.strip(content.asString());
    }

    public void chatStream(String model, List<Map<String, String>> messages, double temperature,
                           Consumer<String> onContent, Consumer<String> onThought) {
        chatStream(model, messages, temperature, chatNumCtx, true, onContent, onThought);
    }

    public void chatStream(String model, List<Map<String, String>> messages, double temperature,
                           boolean thinking, Consumer<String> onContent, Consumer<String> onThought) {
        chatStream(model, messages, temperature, chatNumCtx, thinking, onContent, onThought);
    }

    public void chatStream(String model, List<Map<String, String>> messages, double temperature, int numCtx,
                           Consumer<String> onContent, Consumer<String> onThought) {
        chatStream(model, messages, temperature, numCtx, true, onContent, onThought);
    }

    public void chatStream(String model, List<Map<String, String>> messages, double temperature, int numCtx,
                           boolean thinking, Consumer<String> onContent, Consumer<String> onThought) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("messages", messages);
        body.put("options", Map.of("temperature", temperature, "num_ctx", numCtx));
        body.put("think", thinking);
        body.put("keep_alive", KEEP_ALIVE);

        call(idleTimeout, call -> restClient.post()
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
                            call.touch();
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
                                JsonNode thought = node.at("/message/thinking");
                                if (!thought.isMissingNode() && !thought.isNull()) {
                                    String token = thought.asString();
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
                }));
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

        JsonNode node = call(() -> restClient.post()
                .uri("/api/embed")
                .body(body)
                .retrieve()
                .body(JsonNode.class));
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
        JsonNode node = call(() -> restClient.get()
                .uri("/api/tags")
                .retrieve()
                .body(JsonNode.class));
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
