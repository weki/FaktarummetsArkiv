package se.rightech.fakturummetsarkiv.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        @DefaultValue("http://localhost:11434") String baseUrl,
        @DefaultValue("") String model,
        @DefaultValue("") String embeddingModel,
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("") String validatorModel,
        @DefaultValue("false") boolean validate,
        @DefaultValue("") String refineModel,
        @DefaultValue("") String gateModel,
        @DefaultValue("") String rewriteModel,
        @DefaultValue("16384") int chatNumCtx,
        @DefaultValue("4096") int validatorNumCtx) {
}
