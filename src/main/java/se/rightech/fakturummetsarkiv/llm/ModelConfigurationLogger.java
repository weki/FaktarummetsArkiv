package se.rightech.fakturummetsarkiv.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ModelConfigurationLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigurationLogger.class);

    private final OllamaProperties properties;
    private final RerankProperties rerank;
    private final EffectiveSettings effective;

    public ModelConfigurationLogger(OllamaProperties properties, RerankProperties rerank,
                                    EffectiveSettings effective) {
        this.properties = properties;
        this.rerank = rerank;
        this.effective = effective;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (effective != null) {
            logEffective();
            return;
        }
        logLegacy();
    }

    private void logEffective() {
        List<String> models = configuredModels(effective);
        log.info("Konfigurerade Ollama-modeller ({}): {}", models.size(), String.join(", ", models));

        String chat = effective.effectiveModel("chat");
        int chatCtx = effective.effectiveNumCtx("chat");
        logRole("chat", chat, chatCtx, true, true);
        logRole("validator", effective.effectiveModel("validator"), effective.effectiveNumCtx("validator"),
                effective.hasOwnModel("validator"), effective.hasOwnNumCtx("validator"));
        logRole("gate", effective.effectiveModel("gate"), effective.effectiveNumCtx("gate"),
                effective.hasOwnModel("gate"), effective.hasOwnNumCtx("gate"));
        logRole("rewrite", effective.effectiveModel("rewrite"), effective.effectiveNumCtx("rewrite"),
                effective.hasOwnModel("rewrite"), effective.hasOwnNumCtx("rewrite"));
        logRole("refiner", effective.effectiveModel("refine"), effective.effectiveNumCtx("refine"),
                effective.hasOwnModel("refine"), false);
        logRole("followup", effective.effectiveModel("followup"), effective.effectiveNumCtx("followup"),
                effective.hasOwnModel("followup"), false);
        logRole("rerank", effective.effectiveModel("rerank"), effective.effectiveNumCtx("rerank"),
                effective.hasOwnModel("rerank"), false);

        Map<String, Set<Integer>> ctxsByModel = new HashMap<>();
        add(ctxsByModel, chat, chatCtx);
        add(ctxsByModel, effective.effectiveModel("validator"), effective.effectiveNumCtx("validator"));
        add(ctxsByModel, effective.effectiveModel("refine"), effective.effectiveNumCtx("refine"));
        add(ctxsByModel, effective.effectiveModel("gate"), effective.effectiveNumCtx("gate"));
        add(ctxsByModel, effective.effectiveModel("rewrite"), effective.effectiveNumCtx("rewrite"));
        add(ctxsByModel, effective.effectiveModel("followup"), effective.effectiveNumCtx("followup"));
        add(ctxsByModel, effective.effectiveModel("rerank"), effective.effectiveNumCtx("rerank"));
        for (Map.Entry<String, Set<Integer>> entry : ctxsByModel.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue().size() > 1) {
                log.warn("Modell {} används med olika num_ctx ({}): Ollama laddar om modellen mellan anropen.",
                        entry.getKey(), entry.getValue());
            }
        }
        Double vram = properties.vramGb();
        if (models.size() > 2 && vram != null && vram < 14) {
            log.warn("{} distinkta Ollama-modeller är konfigurerade men ollama.vram-gb={} är under 14 GB. "
                    + "Modellbyten kan bli långsamma eller misslyckas.", models.size(), vram);
        }
    }

    private void logRole(String role, String model, int ctx, boolean ownModel, boolean ownCtx) {
        log.info("Rollkonfiguration {}: model={} ({}) num_ctx={} ({})", role, model,
                ownModel ? "egen" : "ärvd", ctx, ownCtx ? "egen" : "ärvd");
    }

    private void logLegacy() {
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

    private static List<String> configuredModels(EffectiveSettings effective) {
        Set<String> models = new LinkedHashSet<>();
        add(models, effective.effectiveModel("chat"));
        add(models, effective.string("ollama.embedding-model"));
        add(models, effective.effectiveModel("validator"));
        add(models, effective.effectiveModel("refine"));
        add(models, effective.effectiveModel("gate"));
        add(models, effective.effectiveModel("rewrite"));
        add(models, effective.effectiveModel("followup"));
        add(models, effective.effectiveModel("rerank"));
        return List.copyOf(models);
    }

    private static void add(Set<String> models, String model) {
        if (model != null && !model.isBlank()) {
            models.add(model.trim());
        }
    }

    private static void add(Map<String, Set<Integer>> map, String model, int ctx) {
        if (model == null || model.isBlank()) {
            return;
        }
        map.computeIfAbsent(model, k -> new HashSet<>()).add(ctx);
    }
}
