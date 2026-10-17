package se.rightech.fakturummetsarkiv.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaClientTest {

    private static final String BASE = "http://localhost:11434";

    private final OllamaProperties properties = new OllamaProperties(
            BASE, "gemma4:latest", "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true,
            "gemma4:latest", "", "");
    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
    private final OllamaClient client = new OllamaClient(restClientBuilder.baseUrl(BASE).build());

    @Test
    void chats() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"phi3.5:latest","stream":false,"messages":[{"role":"system","content":"sys"},{"role":"user","content":"hej"}],"options":{"temperature":0.0}}
                        """))
                .andRespond(withSuccess("""
                        {"model":"phi3.5:latest","message":{"role":"assistant","content":"svar"},"done":true}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.chat("phi3.5:latest", "sys", "hej")).isEqualTo("svar");
        server.verify();
    }

    @Test
    void embeds() {
        server.expect(requestTo(BASE + "/api/embed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"qllama/multilingual-e5-large","input":["a","b"]}
                        """))
                .andRespond(withSuccess("""
                        {"embeddings":[[0.1,0.2],[0.3,0.4]]}
                        """, MediaType.APPLICATION_JSON));

        List<double[]> vectors = client.embed("qllama/multilingual-e5-large", List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1, 0.2);
        assertThat(vectors.get(1)).containsExactly(0.3, 0.4);
        server.verify();
    }

    @Test
    void streamsTokens() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"message":{"role":"assistant","content":"Hej"},"done":false}
                        {"message":{"role":"assistant","content":"!"},"done":false}
                        {"message":{"role":"assistant","content":""},"done":true}
                        """, MediaType.APPLICATION_NDJSON));

        List<String> tokens = new ArrayList<>();
        client.chatStream("gemma4:latest", List.of(Map.of("role", "user", "content", "hej")), 0.0, tokens::add);

        assertThat(String.join("", tokens)).isEqualTo("Hej!");
        server.verify();
    }

    @Test
    void listsModels() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"models":[{"name":"gemma4:latest"},{"name":"phi3.5:latest"},{"name":"qllama/multilingual-e5-large"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listModels())
                .containsExactly("gemma4:latest", "phi3.5:latest", "qllama/multilingual-e5-large");
        server.verify();
    }
}
