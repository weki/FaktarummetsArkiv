package se.rightech.fakturummetsarkiv.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag.rerank")
public record RerankProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("25") int candidates,
        @DefaultValue("") String model,
        @DefaultValue("600") int maxChars) {
}
