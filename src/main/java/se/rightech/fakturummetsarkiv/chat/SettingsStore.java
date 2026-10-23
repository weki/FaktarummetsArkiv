package se.rightech.fakturummetsarkiv.chat;

import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SettingsStore {

    private final QdrantProperties qdrant;
    private final EffectiveSettings effective;

    private volatile List<String> collections;
    private volatile List<String> documentIds;

    public SettingsStore(QdrantProperties qdrant, EffectiveSettings effective) {
        this.qdrant = qdrant;
        this.effective = effective;
        this.collections = List.of(qdrant.collection());
    }

    public WorkspaceSettings get() {
        return effective.workspace(collections, documentIds);
    }

    public synchronized WorkspaceSettings update(WorkspaceSettings incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("Inga inställningar angivna");
        }
        WorkspaceSettings current = get();
        WorkspaceSettings merged = merge(current, incoming);
        validate(merged);
        apply(incoming);
        if (incoming.collections() != null) {
            collections = clean(incoming.collections());
        }
        if (incoming.documentIds() != null) {
            documentIds = clean(incoming.documentIds());
        }
        return get();
    }

    private void apply(WorkspaceSettings update) {
        set("chat.mode", update.chatMode());
        set("ollama.model", update.chatModel());
        set("ollama.validator-model", update.validatorModel());
        set("chat.validate-grounding", update.validateGrounding());
        set("chat.smart-search", update.smartSearch());
        set("chat.rewrite-query", update.rewriteQuery());
        set("rag.similarity-threshold", update.similarityThreshold());
        set("qdrant.top-k", update.topN());
        set("chat.answer-temperature", update.temperature());
        set("rag.max-history", update.historySize());
        set("ollama.embedding-model", update.embeddingModel());
        set("ollama.refine-model", update.refineModel());
        set("ollama.gate-model", update.gateModel());
        set("ollama.rewrite-model", update.rewriteModel());
        set("chat.answer-language", update.answerLanguage());
    }

    private void set(String key, Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return;
        }
        if (String.valueOf(value).equals(String.valueOf(effective.base(key)))) {
            effective.removeOverride(key);
        } else {
            effective.putOverride(key, value);
        }
    }

    private static WorkspaceSettings merge(WorkspaceSettings base, WorkspaceSettings update) {
        return new WorkspaceSettings(
                pick(update.chatMode(), base.chatMode()),
                pick(update.chatModel(), base.chatModel()),
                pick(update.validatorModel(), base.validatorModel()),
                update.validateGrounding() != null ? update.validateGrounding() : base.validateGrounding(),
                update.smartSearch() != null ? update.smartSearch() : base.smartSearch(),
                update.rewriteQuery() != null ? update.rewriteQuery() : base.rewriteQuery(),
                update.collections() != null ? clean(update.collections()) : base.collections(),
                update.similarityThreshold() != null ? update.similarityThreshold() : base.similarityThreshold(),
                update.topN() != null ? update.topN() : base.topN(),
                update.temperature() != null ? update.temperature() : base.temperature(),
                update.historySize() != null ? update.historySize() : base.historySize(),
                update.documentIds() != null ? clean(update.documentIds()) : base.documentIds(),
                pick(update.embeddingModel(), base.embeddingModel()),
                pick(update.refineModel(), base.refineModel()),
                pick(update.gateModel(), base.gateModel()),
                pick(update.rewriteModel(), base.rewriteModel()),
                pick(update.answerLanguage(), base.answerLanguage()));
    }

    private static List<String> clean(List<String> collections) {
        List<String> result = new ArrayList<>();
        for (String collection : collections) {
            if (collection != null && !collection.isBlank() && !result.contains(collection)) {
                result.add(collection.trim());
            }
        }
        return result;
    }

    private static String pick(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static void validate(WorkspaceSettings settings) {
        Double threshold = settings.similarityThreshold();
        if (threshold != null && (threshold < 0.0 || threshold > 1.0)) {
            throw new IllegalArgumentException("similarityThreshold måste vara mellan 0 och 1");
        }
        Integer topN = settings.topN();
        if (topN != null && (topN < 1 || topN > 50)) {
            throw new IllegalArgumentException("topN måste vara mellan 1 och 50");
        }
        Double temp = settings.temperature();
        if (temp != null && (temp < 0.0 || temp > 2.0)) {
            throw new IllegalArgumentException("temperature måste vara mellan 0 och 2");
        }
        Integer history = settings.historySize();
        if (history != null && (history < 0 || history > 100)) {
            throw new IllegalArgumentException("historySize måste vara mellan 0 och 100");
        }
        if (settings.answerLanguage() != null && !AnswerLanguage.valid(settings.answerLanguage())) {
            throw new IllegalArgumentException("answerLanguage måste vara en, sv eller auto");
        }
        Set<String> models = new LinkedHashSet<>();
        addModel(models, settings.chatModel());
        addModel(models, settings.validatorModel());
        addModel(models, settings.embeddingModel());
        addModel(models, settings.refineModel());
        addModel(models, settings.gateModel());
        addModel(models, settings.rewriteModel());
        if (models.size() > 3) {
            throw new IllegalArgumentException(
                    "Max 3 olika modeller får användas samtidigt (valda: " + String.join(", ", models) + ")");
        }
    }

    private static void addModel(Set<String> models, String model) {
        if (model != null && !model.isBlank()) {
            models.add(model.trim());
        }
    }
}
