package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.ArchiveProperties;
import se.rightech.fakturummetsarkiv.rag.HybridProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsOverrides() {
        EffectiveSettings first = effective();
        SettingsFileStore store = store(tempDir.resolve("settings.json"), first);
        store.save(Map.of("qdrant.top-k", 9, "ollama.model", "phi3.5:latest"));

        assertThat(tempDir.resolve("settings.json")).exists();
        assertThat(tempDir.resolve("settings.json.tmp")).doesNotExist();

        EffectiveSettings second = effective();
        SettingsFileStore reloaded = store(tempDir.resolve("settings.json"), second);
        reloaded.load();

        assertThat(second.integer("qdrant.top-k")).isEqualTo(9);
        assertThat(second.string("ollama.model")).isEqualTo("phi3.5:latest");
    }

    @Test
    void emptyFileMeansNoOverrides() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "");

        EffectiveSettings settings = effective();
        store(file, settings).load();

        assertThat(settings.overrides()).isEmpty();
        assertThat(file).exists();
        assertThat(tempDir.resolve("settings.json.bad")).doesNotExist();
    }

    @Test
    void corruptFileIsRenamedAndIgnored() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{not json");

        EffectiveSettings settings = effective();
        store(file, settings).load();

        assertThat(settings.overrides()).isEmpty();
        assertThat(file).doesNotExist();
        assertThat(tempDir.resolve("settings.json.bad")).exists();
    }

    @Test
    void unknownAndReadOnlyKeysQuarantineTheFile() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{\"version\":1,\"overrides\":{\"qdrant.base-url\":\"http://example\"}}");

        EffectiveSettings settings = effective();
        store(file, settings).load();

        assertThat(settings.overrides()).isEmpty();
        assertThat(tempDir.resolve("settings.json.bad")).exists();
    }

    @Test
    void settingsServicePersistsUpdatesBeforeApplyingThem() {
        EffectiveSettings settings = effective();
        SettingsSchema schema = new SettingsSchema();
        SettingsValidator validator = new SettingsValidator(schema, settings);
        Path file = tempDir.resolve("settings.json");
        SettingsFileStore fileStore = store(file, settings);
        SettingsService service = new SettingsService(schema, settings, validator,
                new MockEnvironment(), fileStore);

        service.update(Map.of("qdrant.top-k", 8), null);

        assertThat(file).exists();
        assertThat(settings.integer("qdrant.top-k")).isEqualTo(8);

        EffectiveSettings reloadedSettings = effective();
        store(file, reloadedSettings).load();
        assertThat(reloadedSettings.integer("qdrant.top-k")).isEqualTo(8);
    }

    private SettingsFileStore store(Path file, EffectiveSettings settings) {
        SettingsSchema schema = new SettingsSchema();
        return new SettingsFileStore(file, settings, schema, new SettingsValidator(schema, settings));
    }

    private static EffectiveSettings effective() {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "phi3.5:latest", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
        return new EffectiveSettings(ollama, qdrant, rag, new ChatProperties(0.1, true, "en"),
                new HybridProperties(false, 50, 60), new RerankProperties(false, 25, "", 600),
                new ArchiveProperties("", "archive"), new MockEnvironment());
    }
}
