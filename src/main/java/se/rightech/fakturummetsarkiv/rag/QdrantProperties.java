package se.rightech.fakturummetsarkiv.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Locale;

@ConfigurationProperties(prefix = "qdrant")
public record QdrantProperties(
        @DefaultValue("http://localhost:6333") String baseUrl,
        @DefaultValue("docs") String collection,
        @DefaultValue("1024") int vectorSize,
        @DefaultValue("Cosine") String distance,
        @DefaultValue("5") int topK,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("query: ") String queryPrefix,
        @DefaultValue("passage: ") String passagePrefix) {

    public boolean usesE5Prefixes(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).contains("e5");
    }

    public String queryInput(String model, String query) {
        return usesE5Prefixes(model) ? queryPrefix + query : query;
    }

    public String passageInput(String model, String text) {
        return usesE5Prefixes(model) ? passagePrefix + text : text;
    }
}
