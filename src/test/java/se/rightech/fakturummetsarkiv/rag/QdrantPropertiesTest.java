package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantPropertiesTest {

    private static QdrantProperties props() {
        return new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 5,
                Duration.ofSeconds(10), "query: ", "passage: ");
    }

    @Test
    void appliesE5Prefixes() {
        QdrantProperties qdrant = props();

        assertThat(qdrant.queryInput("qllama/multilingual-e5-large", "OPC UA"))
                .isEqualTo("query: OPC UA");
        assertThat(qdrant.passageInput("qllama/multilingual-e5-large", "text"))
                .isEqualTo("passage: text");
    }

    @Test
    void leavesOtherModelsUnprefixed() {
        QdrantProperties qdrant = props();

        assertThat(qdrant.queryInput("nomic-embed-text", "OPC UA")).isEqualTo("OPC UA");
        assertThat(qdrant.passageInput("nomic-embed-text", "text")).isEqualTo("text");
    }

    @Test
    void handlesMissingModel() {
        QdrantProperties qdrant = props();

        assertThat(qdrant.queryInput(null, "OPC UA")).isEqualTo("OPC UA");
        assertThat(qdrant.passageInput("", "text")).isEqualTo("text");
    }
}
