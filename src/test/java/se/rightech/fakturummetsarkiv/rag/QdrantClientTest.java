package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantClientTest {

    private static final String BASE = "http://localhost:6333";

    private final QdrantProperties properties = new QdrantProperties(
            BASE, "docs", 1024, "Cosine", 5, Duration.ofSeconds(5), "query: ", "passage:");
    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
    private final QdrantClient client = new QdrantClient(properties, restClientBuilder.baseUrl(BASE).build());

    @Test
    void reportsMissingCollection() {
        server.expect(requestTo(BASE + "/collections/docs"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.collectionExists()).isFalse();
        server.verify();
    }

    @Test
    void createsCollectionWhenMissing() {
        server.expect(requestTo(BASE + "/collections/docs"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/collections/docs"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"vectors":{"size":1024,"distance":"Cosine"}}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.ensureCollection();
        server.verify();
    }

    @Test
    void upsertsPoints() {
        server.expect(requestTo(BASE + "/collections/docs/points?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"points":[{"id":"p1","vector":[0.1,0.2],"payload":{"documentId":"d1","text":"hej"}}]}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.upsert(List.of(new QdrantClient.Point("p1", new double[]{0.1, 0.2},
                Map.of("documentId", "d1", "text", "hej"))));
        server.verify();
    }

    @Test
    void searches() {
        server.expect(requestTo(BASE + "/collections/docs/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"vector":[0.1,0.2],"limit":3,"with_payload":true,"score_threshold":0.25}
                        """))
                .andRespond(withSuccess("""
                        {"result":[{"id":"p1","score":0.91,"payload":{"title":"dok","text":"innehall"}}],"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        List<QdrantClient.ScoredPoint> hits = client.search(new double[]{0.1, 0.2}, 3, 0.25);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isEqualTo(0.91);
        assertThat(hits.get(0).payload()).containsEntry("title", "dok");
        server.verify();
    }

    @Test
    void listsCollections() {
        server.expect(requestTo(BASE + "/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"result":{"collections":[{"name":"docs"},{"name":"manuals"}]},"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listCollections()).containsExactly("docs", "manuals");
        server.verify();
    }

    @Test
    void searchesSelectedCollection() {
        server.expect(requestTo(BASE + "/collections/manuals/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"result":[{"id":"p2","score":0.8,"payload":{"title":"manual","text":"steg"}}],"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        List<QdrantClient.ScoredPoint> hits = client.search("manuals", new double[]{0.3, 0.4}, 5, null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).payload()).containsEntry("title", "manual");
        server.verify();
    }

    @Test
    void deletesByDocument() {
        server.expect(requestTo(BASE + "/collections/docs/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"filter":{"must":[{"key":"documentId","match":{"value":"d1"}}]}}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.deleteByDocument("d1");
        server.verify();
    }

    @Test
    void deletesByArbitraryMatch() {
        server.expect(requestTo(BASE + "/collections/docs/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"filter":{"must":[{"key":"source","match":{"value":"fel.pdf"}}]}}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.deleteByMatch("docs", "source", "fel.pdf");
        server.verify();
    }

    @Test
    void scrollsAllPayloadPages() {
        server.expect(requestTo(BASE + "/collections/docs/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"limit":512,"with_payload":["documentId","source"],"with_vector":false}
                        """))
                .andRespond(withSuccess("""
                        {"result":{"points":[
                            {"id":"p1","payload":{"documentId":"d1","source":"a.pdf"}},
                            {"id":"p2","payload":{"documentId":"d1","source":"a.pdf"}}],
                          "next_page_offset":"p2"},"status":"ok"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/collections/docs/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"limit":512,"with_payload":["documentId","source"],"with_vector":false,"offset":"p2"}
                        """))
                .andRespond(withSuccess("""
                        {"result":{"points":[
                            {"id":"p3","payload":{"documentId":"d2","source":"b.pdf"}}],
                          "next_page_offset":null},"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> payloads = client.scrollPayloads("docs", List.of("documentId", "source"));

        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(2)).containsEntry("source", "b.pdf");
        server.verify();
    }

    @Test
    void scrollReturnsEmptyWhenCollectionMissing() {
        server.expect(requestTo(BASE + "/collections/docs/points/scroll"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.scrollPayloads("docs", List.of("source"))).isEmpty();
        server.verify();
    }

    @Test
    void scrollsWithFilter() {
        server.expect(requestTo(BASE + "/collections/docs/points/scroll"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"limit":512,"with_payload":["text"],"with_vector":false,
                         "filter":{"should":[{"key":"documentId","match":{"value":"d1"}}]}}
                        """))
                .andRespond(withSuccess("""
                        {"result":{"points":[{"id":"p1","payload":{"text":"x"}}],"next_page_offset":null},"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> payloads = client.scrollPayloads("docs", List.of("text"),
                Map.of("should", List.of(Map.of("key", "documentId", "match", Map.of("value", "d1")))));

        assertThat(payloads).hasSize(1);
        server.verify();
    }

    @Test
    void countsPoints() {
        server.expect(requestTo(BASE + "/collections/docs/points/count"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"result":{"count":7},"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.count()).isEqualTo(7);
        server.verify();
    }
}
