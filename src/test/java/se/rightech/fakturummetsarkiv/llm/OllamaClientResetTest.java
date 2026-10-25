package se.rightech.fakturummetsarkiv.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaClientResetTest {

    private HttpServer server;
    private final CountDownLatch requestArrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> unloadRequests = new CopyOnWriteArrayList<>();
    private final CountDownLatch streamClientGone = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/tags", exchange -> {
            requestArrived.countDown();
            await(release);
            respond(exchange, "{\"models\":[]}");
        });
        server.createContext("/api/chat", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"message\":{\"content\":\"hej\"}}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            requestArrived.countDown();
            await(release);
            exchange.close();
        });
        server.createContext("/forever/api/chat", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            requestArrived.countDown();
            try {
                for (int i = 0; i < 600; i++) {
                    out.write("{\"message\":{\"content\":\"x\"}}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException ex) {
                streamClientGone.countDown();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/api/ps", exchange -> respond(exchange,
                "{\"models\":[{\"name\":\"qwen3:8b\"},{\"name\":\"e5:latest\"}]}"));
        server.createContext("/api/generate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("e5:latest")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            unloadRequests.add("generate " + body);
            respond(exchange, "{}");
        });
        server.createContext("/api/embed", exchange -> {
            unloadRequests.add("embed " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private OllamaClient client(Duration timeout) {
        return client("", timeout);
    }

    private OllamaClient client(String basePath, Duration timeout) {
        return new OllamaClient(new OllamaProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + basePath, "", "", timeout, "", false, "", "", "",
                16384, 4096, 2048, 2048, false, false, false, false, false, null));
    }

    @Test
    void resetCancelsCallWaitingForResponse() throws Exception {
        OllamaClient client = client(Duration.ofMinutes(5));
        CompletableFuture<List<String>> pending = CompletableFuture.supplyAsync(client::listModels);
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(client.cancelActiveCalls()).isEqualTo(1);

        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("återställdes");
    }

    @Test
    void resetCancelsStreamMidResponse() throws Exception {
        OllamaClient client = client(Duration.ofMinutes(5));
        List<String> tokens = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> pending = CompletableFuture.runAsync(() -> client.chatStream(
                "qwen3:8b", List.of(Map.of("role", "user", "content", "hej")), 0.0, tokens::add));
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        assertThat(client.cancelActiveCalls()).isEqualTo(1);

        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ResourceAccessException.class);
        assertThat(tokens).containsExactly("hej");
    }

    @Test
    void idleStreamTimesOut() {
        OllamaClient client = client(Duration.ofMillis(800));

        assertThatThrownBy(() -> client.chatStream(
                "qwen3:8b", List.of(Map.of("role", "user", "content", "hej")), 0.0, token -> { }))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("svarade inte");
    }

    @Test
    void resetUnloadsLoadedModels() {
        OllamaClient.ResetResult result = client(Duration.ofSeconds(5)).reset();

        assertThat(result.cancelledRequests()).isZero();
        assertThat(result.unloadedModels()).containsExactly("qwen3:8b", "e5:latest");
        assertThat(result.failedModels()).isEmpty();
        assertThat(unloadRequests).hasSize(2);
        assertThat(unloadRequests.get(0)).startsWith("generate").contains("\"keep_alive\":0");
        assertThat(unloadRequests.get(1)).startsWith("embed").contains("e5:latest").contains("\"keep_alive\":0");
    }

    @Test
    void threadIsNotLeftInterruptedAfterCancel() throws Exception {
        OllamaClient client = client(Duration.ofMinutes(5));
        CompletableFuture<Boolean> interruptedAfter = CompletableFuture.supplyAsync(() -> {
            try {
                client.listModels();
            } catch (ResourceAccessException expected) {
                // cancelled
            }
            return Thread.currentThread().isInterrupted();
        });
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();
        client.cancelActiveCalls();

        assertThat(interruptedAfter.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void cancelledScopeRejectsLaterCallsWithoutContactingOllama() {
        OllamaClient client = client(Duration.ofSeconds(5));
        OllamaClient.Scope scope = client.openScope();
        client.cancelActiveCalls();

        assertThatThrownBy(() -> client.within(scope, client::listModels).get())
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("återställdes");
        assertThat(requestArrived.getCount()).isEqualTo(1);

        OllamaClient.Scope fresh = client.openScope();
        release.countDown();
        assertThat(client.within(fresh, client::listModels).get()).isEmpty();
    }

    @Test
    void cancellingClosesConnectionSoOllamaStopsGenerating() throws Exception {
        OllamaClient client = client("/forever", Duration.ofMinutes(5));
        CompletableFuture<Void> pending = CompletableFuture.runAsync(() -> client.chatStream(
                "qwen3:8b", List.of(Map.of("role", "user", "content", "hej")), 0.0, token -> { }));
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        client.cancelActiveCalls();

        assertThat(streamClientGone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ResourceAccessException.class);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
