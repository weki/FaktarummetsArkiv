package se.rightech.fakturummetsarkiv.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag.hybrid")
public record HybridProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("50") int keywordLimit,
        @DefaultValue("60") int rrfK) {
}
