package se.rightech.fakturummetsarkiv.chat;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.rag.ArchiveProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SettingsFileStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsFileStore.class);
    private static final int FORMAT_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path path;
    private final EffectiveSettings effective;
    private final SettingsSchema schema;
    private final SettingsValidator validator;

    @Autowired
    public SettingsFileStore(ArchiveProperties archive, EffectiveSettings effective, SettingsSchema schema,
                             SettingsValidator validator, @Value("${settings.file:}") String configuredPath) {
        this(resolvePath(archive, configuredPath), effective, schema, validator);
    }

    SettingsFileStore(Path path, EffectiveSettings effective, SettingsSchema schema,
                      SettingsValidator validator) {
        this.path = path.toAbsolutePath().normalize();
        this.effective = effective;
        this.schema = schema;
        this.validator = validator;
    }

    @PostConstruct
    void init() {
        load();
    }

    public Path path() {
        return path;
    }

    public synchronized void save(Map<String, Object> overrides) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (value != null) {
                    safe.put(key, value);
                }
            });
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), new SettingsFile(FORMAT_VERSION, safe));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Kunde inte spara inställningar till " + path + ": "
                    + exception.getMessage(), exception);
        }
    }

    public synchronized void load() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            String content = Files.readString(path);
            if (content.isBlank()) {
                return;
            }
            SettingsFile file = JSON.readValue(content, SettingsFile.class);
            if (file == null || file.version() != FORMAT_VERSION || file.overrides() == null) {
                quarantine("fel formatversion");
                return;
            }
            Map<String, Object> overrides = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : file.overrides().entrySet()) {
                SettingDefinition definition = schema.find(entry.getKey());
                if (definition == null || definition.readOnly()) {
                    quarantine("okänd eller skrivskyddad nyckel " + entry.getKey());
                    return;
                }
                overrides.put(entry.getKey(), entry.getValue());
            }
            Map<String, Object> normalized = overrides.isEmpty() ? Map.of() : validator.normalize(overrides);
            Map<String, Object> applied = inheritedValues(normalized, effective);
            effective.replaceOverrides(applied);
        } catch (IOException | RuntimeException exception) {
            quarantine(exception.getMessage());
        }
    }

    /**
     * Värden som är lika med chattmodellen/chattens kontext tolkas som ärvda i minnet.
     * Filen skrivs inte om, så en äldre konfiguration kan läsas oförändrad.
     */
    static Map<String, Object> inheritedValues(Map<String, Object> normalized, EffectiveSettings effective) {
        Map<String, Object> applied = new LinkedHashMap<>();
        normalized.forEach((key, value) -> {
            if (value != null) {
                applied.put(key, value);
            }
        });
        String chatModel = applied.containsKey("ollama.model")
                ? String.valueOf(applied.get("ollama.model"))
                : effective.string("ollama.model");
        int chatCtx = applied.containsKey("ollama.chat-num-ctx")
                ? toInt(applied.get("ollama.chat-num-ctx"))
                : effective.effectiveNumCtx("chat");
        for (String key : List.of("ollama.validator-model", "ollama.refine-model",
                "ollama.gate-model", "ollama.rewrite-model", "ollama.followup-model",
                "rag.rerank.model")) {
            Object value = applied.get(key);
            if (value == null || String.valueOf(value).isBlank()
                    || String.valueOf(value).trim().equals(chatModel == null ? "" : chatModel.trim())) {
                applied.remove(key);
            }
        }
        for (String key : List.of("ollama.validator-num-ctx", "ollama.gate-num-ctx",
                "ollama.rewrite-num-ctx")) {
            Object value = applied.get(key);
            if (value != null && toInt(value) == chatCtx) {
                applied.remove(key);
            }
        }
        return applied;
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null || String.valueOf(value).isBlank() ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void quarantine(String reason) {
        Path bad = path.resolveSibling(path.getFileName() + ".bad");
        try {
            Files.move(path, bad, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Inställningsfilen {} kunde inte läsas ({}). Flyttad till {}", path, reason, bad);
        } catch (IOException exception) {
            log.warn("Inställningsfilen {} kunde inte läsas ({}). Kunde inte flytta den: {}",
                    path, reason, exception.getMessage());
        }
    }

    private static Path resolvePath(ArchiveProperties archive, String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath.trim());
        }
        Path archivePath = archive.uploadPath();
        Path parent = archivePath.getParent();
        return (parent == null ? Path.of(".") : parent).resolve("settings.json");
    }

    public record SettingsFile(int version, Map<String, Object> overrides) {
    }
}
