package se.rightech.fakturummetsarkiv.chat;

import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SettingsStore {

    private final QdrantProperties qdrant;
    private final OllamaProperties ollama;
    private final RagProperties rag;

    private volatile WorkspaceSettings current;

    public SettingsStore(QdrantProperties qdrant, OllamaProperties ollama, RagProperties rag) {
        this.qdrant = qdrant;
        this.ollama = ollama;
        this.rag = rag;
        this.current = defaults();
    }

    private WorkspaceSettings defaults() {
        return new WorkspaceSettings(
                "chat",
                ollama.model(),
                ollama.validatorModel(),
                ollama.validate(),
                !ollama.gateModel().isBlank(),
                true,
                List.of(qdrant.collection()),
                rag.similarityThreshold(),
                qdrant.topK(),
                0.0,
                rag.maxHistory(),
                null,
                ollama.embeddingModel(),
                ollama.refineModel(),
                ollama.gateModel(),
                ollama.rewriteModel());
    }

    public WorkspaceSettings get() {
        return current;
    }

    public synchronized WorkspaceSettings update(WorkspaceSettings incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("Inga inställningar angivna");
        }
        WorkspaceSettings merged = merge(current, incoming);
        validate(merged);
        current = merged;
        return current;
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
                pick(update.rewriteModel(), base.rewriteModel()));
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
