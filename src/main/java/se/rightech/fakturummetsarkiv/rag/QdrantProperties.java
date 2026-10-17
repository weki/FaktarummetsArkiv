package se.rightech.fakturummetsarkiv.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "qdrant")
public record QdrantProperties(
        @DefaultValue("http://localhost:6333") String baseUrl,
        @DefaultValue("docs") String collection,
        @DefaultValue("1024") int vectorSize,
        @DefaultValue("Cosine") String distance,
        @DefaultValue("5") int topK,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("query: ") String queryPrefix,
        @DefaultValue("passage:") String passagePrefix) {
}
