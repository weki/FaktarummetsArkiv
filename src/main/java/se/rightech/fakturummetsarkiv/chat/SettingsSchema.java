package se.rightech.fakturummetsarkiv.chat;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SettingsSchema {

    private final List<SettingsGroup> groups;
    private final List<SettingDefinition> definitions;
    private final Map<String, SettingDefinition> byKey;

    public SettingsSchema() {
        this.groups = List.of(
                new SettingsGroup("models", "Modeller", "Modeller för chat, embeddings, validering och hjälpsteg.", 10),
                new SettingsGroup("search", "Sökning", "Sökgate, hybridsökning, rerankning och urval av källor.", 20),
                new SettingsGroup("answer", "Svar", "Svarsläge, temperatur, historik och källbudget.", 30),
                new SettingsGroup("validation", "Validering", "Grundningskontroll och modellernas thinking-läge.", 40),
                new SettingsGroup("context", "Kontext och VRAM", "Kontextfönster per roll och GPU-budget.", 50),
                new SettingsGroup("indexing", "Indexering", "Chunkning och dokumentlagring. Kräver omindexering för gamla dokument.", 60),
                new SettingsGroup("limits", "Gränser", "Uppladdning, requeststorlek och arkivsökvägar.", 70),
                new SettingsGroup("infrastructure", "Infrastruktur", "Ollama, Qdrant och övrig drift. Ändras i konfigurationen och kräver omstart.", 80));

        List<SettingDefinition> list = List.of(
                model("ollama.model", "models", "Chattmodell", "Huvudmodell för svar och följdfrågor.", "qwen3:8b"),
                model("ollama.validator-model", "models", "Valideringsmodell", "Modell som kontrollerar att svaret stöds av källorna.", "qwen3:8b"),
                embedding("ollama.embedding-model", "models", "Embeddingmodell", "Måste ge samma vektordimension som Qdrant-samlingen.", "qllama/multilingual-e5-large"),
                model("ollama.refine-model", "models", "Förfiningsmodell", "Modell som skriver om svaret vid underkänd validering.", "qwen3:8b"),
                model("ollama.gate-model", "models", "Sökgatemodell", "Liten modell som avgör om sökning behövs.", "phi3.5:latest"),
                model("ollama.rewrite-model", "models", "Omformuleringsmodell", "Liten modell som gör fristående sökfrågor av följdfrågor.", "phi3.5:latest"),
                model("rag.rerank.model", "models", "Rerankmodell", "Tomt värde använder chattmodellen.", ""),

                bool("chat.smart-search", "search", "Smart sökning", "Låt sökgaten avgöra om dokument behöver sökas.", true, ApplyMode.LIVE, null, null),
                text("rag.gate.mode", "search", "Sökgate", "model använder en modell, rules använder enkla regler och off söker alltid.", "model", ApplyMode.LIVE, null, null),
                bool("chat.rewrite-query", "search", "Omformulera följdfrågor", "Gör följdfrågor fristående innan sökning.", true, ApplyMode.LIVE, null, null),
                text("rag.search-language", "search", "Sökspråk", "Språk som skickas i embeddingsfrågan.", "en", ApplyMode.LIVE, null, null),
                integer("qdrant.top-k", "search", "Antal källor", "Max antal sökträffar som skickas vidare.", 5, 1, 50, 1, ApplyMode.LIVE, null, null),
                decimal("rag.similarity-threshold", "search", "Likhetströskel", "Lägsta likhet för att en träff ska räknas som källa.", 0.3, 0, 1, 0.05, ApplyMode.LIVE, null, null),
                bool("rag.hybrid.enabled", "search", "Hybridsökning", "Kombinera vektorsökning med nyckelord.", false, ApplyMode.LIVE, null, null),
                integer("rag.hybrid.keyword-limit", "search", "Nyckelordskandidater", "Antal nyckelordsträffar i hybridsökningen.", 50, 1, 500, 1, ApplyMode.LIVE, "rag.hybrid.enabled", null),
                integer("rag.hybrid.rrf-k", "search", "RRF-k", "Konstant i Reciprocal Rank Fusion.", 60, 1, 1000, 1, ApplyMode.LIVE, "rag.hybrid.enabled", null),
                bool("rag.rerank.enabled", "search", "Rerankning", "Låt en modell rangordna om kandidaterna.", false, ApplyMode.LIVE, null, null),
                integer("rag.rerank.candidates", "search", "Rerankkandidater", "Antal träffar som rerankas.", 25, 1, 100, 1, ApplyMode.LIVE, "rag.rerank.enabled", null),
                integer("rag.rerank.max-chars", "search", "Rerank textgräns", "Max tecken per kandidat vid rerankning.", 600, 80, 4000, 10, ApplyMode.LIVE, "rag.rerank.enabled", null),

                enumValue("chat.mode", "answer", "Chattläge", "chat svarar brett, query är strikt dokumentfråga.", "chat", List.of("chat", "query")),
                decimal("chat.answer-temperature", "answer", "Temperatur", "Lägre värde ger mer förutsägbara svar.", 0.1, 0, 2, 0.1, ApplyMode.LIVE, null, null),
                integer("rag.max-history", "answer", "Historik", "Antal tidigare meddelanden som skickas med.", 20, 0, 100, 1, ApplyMode.LIVE, null, null),
                integer("rag.max-history-chars", "answer", "Historiktecken", "Max antal tecken historik som skickas till modellen.", 4000, 0, 100000, 100, ApplyMode.LIVE, null, null),
                integer("rag.max-source-chars", "answer", "Källtecken", "Max antal tecken källmaterial som skickas till modellen.", 8000, 0, 100000, 100, ApplyMode.LIVE, null, null),
                enumValue("chat.answer-language", "answer", "Svarspråk", "en, sv eller auto (samma som frågan).", "en", List.of("en", "sv", "auto")),
                bool("chat.thinking", "answer", "Thinking för svar", "Låt chattmodellen använda thinking.", true, ApplyMode.LIVE, null, null),

                bool("chat.validate-grounding", "validation", "Grundningskontroll", "Kontrollera att svaret stöds av källorna.", true, ApplyMode.LIVE, null, null),
                bool("ollama.validator-thinking", "validation", "Thinking för validering", "Slå på thinking i valideringsmodellen.", false, ApplyMode.LIVE, null, null),
                bool("ollama.refine-thinking", "validation", "Thinking för förfinning", "Slå på thinking när svaret förfinas.", false, ApplyMode.LIVE, null, null),

                integer("ollama.chat-num-ctx", "context", "Kontext: chatt", "Kontextfönster för chattmodellen.", 8192, 512, 131072, 512, ApplyMode.MODEL_RELOAD, null, "Ändrat kontextfönster laddar om modellen."),
                integer("ollama.validator-num-ctx", "context", "Kontext: validering", "Kontextfönster för valideringsmodellen.", 8192, 512, 131072, 512, ApplyMode.MODEL_RELOAD, null, "Ändrat kontextfönster laddar om modellen."),
                integer("ollama.gate-num-ctx", "context", "Kontext: gate", "Kontextfönster för sökgaten.", 2048, 512, 131072, 512, ApplyMode.MODEL_RELOAD, null, "Ändrat kontextfönster laddar om modellen."),
                integer("ollama.rewrite-num-ctx", "context", "Kontext: omformulering", "Kontextfönster för omformulering.", 2048, 512, 131072, 512, ApplyMode.MODEL_RELOAD, null, "Ändrat kontextfönster laddar om modellen."),
                bool("ollama.gate-thinking", "context", "Thinking: gate", "Thinking-läge för sökgaten.", false, ApplyMode.LIVE, null, null),
                bool("ollama.rewrite-thinking", "context", "Thinking: omformulering", "Thinking-läge för omformulering.", false, ApplyMode.LIVE, null, null),
                bool("ollama.followup-thinking", "context", "Thinking: följdfrågor", "Thinking-läge för följdfrågor.", false, ApplyMode.LIVE, null, null),
                decimal("ollama.vram-gb", "context", "GPU-budget (GB)", "Valfri budget för varning när fler än två modeller konfigurerats.", null, 0, 1000, 0.5, ApplyMode.RESTART, null, null),

                integer("rag.chunk-size", "indexing", "Chunkstorlek", "Tecken per chunk. Påverkar nya dokument; gamla kräver omindexering.", 1200, 100, 20000, 50, ApplyMode.REINDEX, null, "Gamla dokument behöver indexeras om för att få ny chunkning."),
                integer("rag.chunk-overlap", "indexing", "Chunköverlapp", "Överlapp mellan chunks.", 200, 0, 10000, 10, ApplyMode.REINDEX, null, "Gamla dokument behöver indexeras om för att få ny chunkning."),

                readOnly(path("archive.upload-dir", "indexing", "Uppladdningsmapp", "Mapp där originaldokument sparas.", "archive", ApplyMode.RESTART)),
                readOnly(text("archive.pdf-roots", "limits", "Arkivsökvägar", "Kommaavgränsade PDF-rötter som får visas och laddas ner.", "", ApplyMode.RESTART, null, null)),
                readOnly(text("spring.servlet.multipart.max-file-size", "limits", "Max filstorlek", "Största tillåtna uppladdning.", "100MB", ApplyMode.RESTART, null, null)),
                readOnly(text("spring.servlet.multipart.max-request-size", "limits", "Max requeststorlek", "Största tillåtna request.", "500MB", ApplyMode.RESTART, null, null)),
                readOnly(text("server.tomcat.max-swallow-size", "limits", "Max swallow", "Övre gräns för tomma eller skadade requests.", "512MB", ApplyMode.RESTART, null, null)),
                readOnly(text("spring.codec.max-in-memory-size", "limits", "Max buffert", "Max in-memory-storlek för codec.", "512MB", ApplyMode.RESTART, null, null)),

                readOnly(text("ollama.base-url", "infrastructure", "Ollama URL", "Adress till Ollama.", "http://localhost:11434", ApplyMode.RESTART, null, null)),
                readOnly(text("ollama.timeout", "infrastructure", "Ollama timeout", "Timeout för Ollama-anrop.", "300s", ApplyMode.RESTART, null, null)),
                readOnly(text("qdrant.base-url", "infrastructure", "Qdrant URL", "Adress till Qdrant.", "http://localhost:6333", ApplyMode.RESTART, null, null)),
                readOnly(text("qdrant.collection", "infrastructure", "Qdrant-samling", "Standardsamling för sökning.", "docs", ApplyMode.RESTART, null, null)),
                readOnly(integer("qdrant.vector-size", "infrastructure", "Vektordimension", "Måste matcha embeddingmodellen.", 1024, 1, 65536, 1, ApplyMode.REINDEX, null, null)),
                readOnly(text("qdrant.distance", "infrastructure", "Distansmått", "Cosinus, Dot eller Euclid.", "Cosine", ApplyMode.REINDEX, null, null)),
                readOnly(text("qdrant.timeout", "infrastructure", "Qdrant timeout", "Timeout för Qdrant-anrop.", "10s", ApplyMode.RESTART, null, null)),
                readOnly(text("qdrant.query-prefix", "infrastructure", "Query-prefix", "Prefix för sökfrågor.", "query: ", ApplyMode.REINDEX, null, null)),
                readOnly(text("qdrant.passage-prefix", "infrastructure", "Passage-prefix", "Prefix för indexerade passager.", "passage: ", ApplyMode.REINDEX, null, null))
        );

        this.definitions = List.copyOf(list);
        Map<String, SettingDefinition> map = new LinkedHashMap<>();
        for (SettingDefinition definition : definitions) {
            map.put(definition.key(), definition);
        }
        this.byKey = Map.copyOf(map);
    }

    public List<SettingsGroup> groups() {
        return groups;
    }

    public List<SettingDefinition> definitions() {
        return definitions;
    }

    public SettingDefinition require(String key) {
        SettingDefinition definition = byKey.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Okänd inställning: " + key);
        }
        return definition;
    }

    public SettingDefinition find(String key) {
        return byKey.get(key);
    }

    private static SettingDefinition model(String key, String group, String label, String description, String defaultValue) {
        return new SettingDefinition(key, group, label, description, SettingsType.MODEL, defaultValue,
                null, null, null, null, null, ApplyMode.MODEL_RELOAD, null,
                "Byte av modell laddar om modellen vid nästa anrop.", false, false);
    }

    private static SettingDefinition embedding(String key, String group, String label, String description, String defaultValue) {
        return new SettingDefinition(key, group, label, description, SettingsType.MODEL, defaultValue,
                null, null, null, null, null, ApplyMode.REINDEX, null,
                "Embeddingmodellen måste matcha vektordimensionen. Byte kräver omindexering.", false, false);
    }

    private static SettingDefinition bool(String key, String group, String label, String description,
                                          boolean defaultValue, ApplyMode applyMode, String dependsOn, String warning) {
        return new SettingDefinition(key, group, label, description, SettingsType.BOOLEAN, defaultValue,
                null, null, null, null, null, applyMode, dependsOn, warning, false, false);
    }

    private static SettingDefinition integer(String key, String group, String label, String description,
                                             int defaultValue, double min, double max, double step,
                                             ApplyMode applyMode, String dependsOn, String warning) {
        return new SettingDefinition(key, group, label, description, SettingsType.INTEGER, defaultValue,
                null, min, max, step, null, applyMode, dependsOn, warning, false, false);
    }

    private static SettingDefinition decimal(String key, String group, String label, String description,
                                             Double defaultValue, double min, double max, double step,
                                             ApplyMode applyMode, String dependsOn, String warning) {
        return new SettingDefinition(key, group, label, description, SettingsType.DECIMAL, defaultValue,
                null, min, max, step, null, applyMode, dependsOn, warning, false, false);
    }

    private static SettingDefinition text(String key, String group, String label, String description,
                                          String defaultValue, ApplyMode applyMode, String dependsOn, String warning) {
        return new SettingDefinition(key, group, label, description, SettingsType.TEXT, defaultValue,
                null, null, null, null, null, applyMode, dependsOn, warning, false, false);
    }

    private static SettingDefinition enumValue(String key, String group, String label, String description,
                                               String defaultValue, List<String> allowedValues) {
        return new SettingDefinition(key, group, label, description, SettingsType.ENUM, defaultValue,
                null, null, null, null, allowedValues, ApplyMode.LIVE, null, null, false, false);
    }

    private static SettingDefinition path(String key, String group, String label, String description,
                                          String defaultValue, ApplyMode applyMode) {
        return new SettingDefinition(key, group, label, description, SettingsType.PATH, defaultValue,
                null, null, null, null, null, applyMode, null, null, false, false);
    }

    private static SettingDefinition readOnly(SettingDefinition definition) {
        return new SettingDefinition(definition.key(), definition.group(), definition.label(),
                definition.description(), definition.type(), definition.defaultValue(), definition.unit(),
                definition.min(), definition.max(), definition.step(), definition.allowedValues(),
                definition.applyMode(), definition.dependsOn(), definition.warning(), true, false);
    }
}
