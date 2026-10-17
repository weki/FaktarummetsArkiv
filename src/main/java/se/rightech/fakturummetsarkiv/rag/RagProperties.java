package se.rightech.fakturummetsarkiv.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("20") int maxHistory,
        @DefaultValue("1200") int chunkSize,
        @DefaultValue("200") int chunkOverlap,
        @DefaultValue("8000") int maxSourceChars) {
}
