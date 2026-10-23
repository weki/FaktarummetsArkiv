package se.rightech.fakturummetsarkiv.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ModelConfigurationLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigurationLogger.class);

    private final OllamaProperties properties;
    private final RerankProperties rerank;

    public ModelConfigurationLogger(OllamaProperties properties, RerankProperties rerank) {
        this.properties = properties;
        this.rerank = rerank;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> models = configuredModels(properties);
        log.info("Konfigurerade Ollama-modeller ({}): {}", models.size(), String.join(", ", models));
        log.info("Rollkonfiguration chat: model={} num_ctx={}", properties.model(), properties.chatNumCtx());
        log.info("Rollkonfiguration validator: model={} num_ctx={}",
                properties.validatorModel(), properties.validatorNumCtx());
        log.info("Rollkonfiguration gate: model={} num_ctx={}",
                properties.gateModel(), properties.gateNumCtx());
        log.info("Rollkonfiguration rewrite: model={} num_ctx={}",
                properties.rewriteModel(), properties.rewriteNumCtx());
        log.info("Rollkonfiguration refiner: model={} num_ctx={}",
                properties.refineModel(), properties.chatNumCtx());
        log.info("Rollkonfiguration followup: model={} num_ctx={}",
                followupModel(), properties.chatNumCtx());
        log.info("Rollkonfiguration rerank: model={} num_ctx={}",
                rerankModel(), properties.chatNumCtx());
        Double vram = properties.vramGb();
        if (models.size() > 2 && vram != null && vram < 14) {
            log.warn("{} distinkta Ollama-modeller är konfigurerade men ollama.vram-gb={} är under 14 GB. "
                    + "Modellbyten kan bli långsamma eller misslyckas.", models.size(), vram);
        }
    }

    private String followupModel() {
        return properties.refineModel() != null && !properties.refineModel().isBlank()
                ? properties.refineModel() : properties.model();
    }

    private String rerankModel() {
        if (rerank != null && rerank.model() != null && !rerank.model().isBlank()) {
            return rerank.model();
        }
        return properties.model();
    }

    static List<String> configuredModels(OllamaProperties properties) {
        Set<String> models = new LinkedHashSet<>();
        add(models, properties.model());
        add(models, properties.embeddingModel());
        add(models, properties.validatorModel());
        add(models, properties.refineModel());
        add(models, properties.gateModel());
        add(models, properties.rewriteModel());
        return List.copyOf(models);
    }

    private static void add(Set<String> models, String model) {
        if (model != null && !model.isBlank()) {
            models.add(model.trim());
        }
    }
}
