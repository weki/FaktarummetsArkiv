package se.rightech.fakturummetsarkiv.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.ArchiveProperties;
import se.rightech.fakturummetsarkiv.rag.HybridProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Effektiva värden: UI-override om det finns, annars deploymentens konfiguration.
 * Tjänster ska läsa härifrån per request så att ändringar slår igenom utan omstart.
 */
@Service
public class EffectiveSettings {

    private final OllamaProperties ollama;
    private final QdrantProperties qdrant;
    private final RagProperties rag;
    private final ChatProperties chat;
    private final HybridProperties hybrid;
    private final RerankProperties rerank;
    private final ArchiveProperties archive;
    private final Environment environment;

    private final Map<String, Object> overrides = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    @Autowired
    public EffectiveSettings(OllamaProperties ollama, QdrantProperties qdrant, RagProperties rag,
                             ChatProperties chat, HybridProperties hybrid, RerankProperties rerank,
                             ArchiveProperties archive, Environment environment) {
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.rag = rag;
        this.chat = chat;
        this.hybrid = hybrid;
        this.rerank = rerank;
        this.archive = archive;
        this.environment = environment;
    }

    public EffectiveSettings(OllamaProperties ollama, QdrantProperties qdrant, RagProperties rag,
                             ChatProperties chat, HybridProperties hybrid, RerankProperties rerank) {
        this(ollama, qdrant, rag, chat, hybrid, rerank, new ArchiveProperties("", "archive"), null);
    }


    public static EffectiveSettings baseline(OllamaProperties ollama, RagProperties rag) {
        return baseline(ollama, rag, new ChatProperties(0.1, true, "en"));
    }

    public static EffectiveSettings baseline(OllamaProperties ollama, RagProperties rag, ChatProperties chat) {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 5,
                Duration.ofSeconds(10), "query: ", "passage: ");
        return new EffectiveSettings(ollama, qdrant, rag, chat, new HybridProperties(false, 50, 60),
                new RerankProperties(false, 25, "", 600));
    }

    public Object value(String key) {
        if (overrides.containsKey(key)) {
            return overrides.get(key);
        }
        return base(key);
    }

    public Object base(String key) {
        return switch (key) {
            case "chat.mode" -> "chat";
            case "chat.model", "ollama.model" -> ollama.model();
            case "chat.validator-model", "ollama.validator-model" -> ollama.validatorModel();
            case "chat.embedding-model", "ollama.embedding-model" -> ollama.embeddingModel();
            case "chat.refine-model", "ollama.refine-model" -> ollama.refineModel();
            case "chat.gate-model", "ollama.gate-model" -> ollama.gateModel();
            case "chat.rewrite-model", "ollama.rewrite-model" -> ollama.rewriteModel();
            case "chat.validate-grounding" -> ollama.validate();
            case "chat.smart-search" -> !ollama.gateModel().isBlank() || !"model".equalsIgnoreCase(rag.gateMode());
            case "chat.rewrite-query" -> true;
            case "chat.answer-temperature" -> chat.answerTemperature();
            case "chat.answer-language" -> chat.answerLanguage();
            case "chat.thinking" -> chat.thinking();
            case "rag.max-history" -> rag.maxHistory();
            case "rag.max-history-chars" -> rag.maxHistoryChars();
            case "rag.max-source-chars" -> rag.maxSourceChars();
            case "rag.search-language" -> rag.searchLanguage();
            case "rag.similarity-threshold" -> rag.similarityThreshold();
            case "rag.gate.mode" -> rag.gateMode();
            case "rag.chunk-size" -> rag.chunkSize();
            case "rag.chunk-overlap" -> rag.chunkOverlap();
            case "rag.neighbor-chunks" -> 1;
            case "rag.hybrid.enabled" -> hybrid.enabled();
            case "rag.hybrid.keyword-limit" -> hybrid.keywordLimit();
            case "rag.hybrid.rrf-k" -> hybrid.rrfK();
            case "rag.rerank.enabled" -> rerank.enabled();
            case "rag.rerank.candidates" -> rerank.candidates();
            case "rag.rerank.model" -> rerank.model();
            case "rag.rerank.max-chars" -> rerank.maxChars();
            case "qdrant.top-k" -> qdrant.topK();
            case "ollama.chat-num-ctx" -> ollama.chatNumCtx();
            case "ollama.validator-num-ctx" -> ollama.validatorNumCtx();
            case "ollama.gate-num-ctx" -> ollama.gateNumCtx();
            case "ollama.rewrite-num-ctx" -> ollama.rewriteNumCtx();
            case "ollama.validator-thinking" -> ollama.validatorThinking();
            case "ollama.refine-thinking" -> ollama.refineThinking();
            case "ollama.gate-thinking" -> ollama.gateThinking();
            case "ollama.rewrite-thinking" -> ollama.rewriteThinking();
            case "ollama.followup-thinking" -> ollama.followupThinking();
            case "ollama.vram-gb" -> ollama.vramGb();
            case "ollama.base-url" -> ollama.baseUrl();
            case "ollama.timeout" -> duration(ollama.timeout());
            case "qdrant.base-url" -> qdrant.baseUrl();
            case "qdrant.collection" -> qdrant.collection();
            case "qdrant.vector-size" -> qdrant.vectorSize();
            case "qdrant.distance" -> qdrant.distance();
            case "qdrant.timeout" -> duration(qdrant.timeout());
            case "qdrant.query-prefix" -> qdrant.queryPrefix();
            case "qdrant.passage-prefix" -> qdrant.passagePrefix();
            case "archive.upload-dir" -> archive.uploadDir();
            case "archive.pdf-roots" -> archive.pdfRoots();
            case "gpu.stats-url" -> "";
            case "gpu.poll-interval-ms" -> 2000;
            case "gpu.history-enabled" -> true;
            case "gpu.history-hours" -> 24;
            case "gpu.history-sample-seconds" -> 5;
            case "gpu.restart-enabled" -> false;
            case "gpu.restart-token" -> "";
            case "spring.servlet.multipart.max-file-size", "spring.servlet.multipart.max-request-size",
                 "server.tomcat.max-swallow-size", "spring.codec.max-in-memory-size" ->
                    environment == null ? null : environment.getProperty(key);
            default -> throw new IllegalArgumentException("Okänd inställning: " + key);
        };
    }

    public String string(String key) {
        Object value = value(key);
        return value == null ? null : String.valueOf(value);
    }

    public boolean bool(String key) {
        Object value = value(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    public int integer(String key) {
        Object value = value(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null || String.valueOf(value).isBlank() ? 0 : Integer.parseInt(String.valueOf(value));
    }

    public double decimal(String key) {
        Object value = value(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null || String.valueOf(value).isBlank() ? 0.0 : Double.parseDouble(String.valueOf(value));
    }

    public WorkspaceSettings workspace(List<String> collections, List<String> documentIds) {
        return new WorkspaceSettings(
                string("chat.mode"),
                string("ollama.model"),
                string("ollama.validator-model"),
                bool("chat.validate-grounding"),
                bool("chat.smart-search"),
                bool("chat.rewrite-query"),
                collections,
                decimal("rag.similarity-threshold"),
                integer("qdrant.top-k"),
                decimal("chat.answer-temperature"),
                integer("rag.max-history"),
                documentIds,
                string("ollama.embedding-model"),
                string("ollama.refine-model"),
                string("ollama.gate-model"),
                string("ollama.rewrite-model"),
                string("chat.answer-language"));
    }

    public Map<String, Object> overrides() {
        return Map.copyOf(overrides);
    }

    public long revision() {
        return revision.get();
    }

    public void putOverride(String key, Object value) {
        if (value == null) {
            overrides.remove(key);
        } else {
            overrides.put(key, value);
        }
        revision.incrementAndGet();
    }

    public void removeOverride(String key) {
        overrides.remove(key);
        revision.incrementAndGet();
    }

    public void replaceOverrides(Map<String, Object> values) {
        overrides.clear();
        if (values != null) {
            values.forEach(this::putOverride);
        }
        revision.incrementAndGet();
    }

    private static String duration(Duration value) {
        if (value == null) {
            return null;
        }
        long millis = value.toMillis();
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return millis + "ms";
    }

    Map<String, Object> snapshotOverrides() {
        return new LinkedHashMap<>(overrides);
    }
}
