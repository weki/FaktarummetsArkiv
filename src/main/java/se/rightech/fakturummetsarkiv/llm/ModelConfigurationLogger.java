package se.rightech.fakturummetsarkiv.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ModelConfigurationLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigurationLogger.class);

    private final OllamaProperties properties;

    public ModelConfigurationLogger(OllamaProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> models = configuredModels(properties);
        log.info("Konfigurerade Ollama-modeller ({}): {}", models.size(), String.join(", ", models));
        Double vram = properties.vramGb();
        if (models.size() > 2 && vram != null && vram < 14) {
            log.warn("{} distinkta Ollama-modeller är konfigurerade men ollama.vram-gb={} är under 14 GB. "
                    + "Modellbyten kan bli långsamma eller misslyckas.", models.size(), vram);
        }
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
