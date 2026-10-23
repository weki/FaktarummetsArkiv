package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsContractTest {

    @TempDir
    Path tempDir;

    private final SettingsSchema schema = new SettingsSchema();

    @Test
    void applyModesMatchVerifiedBehaviour() {
        assertThat(schema.require("chat.answer-temperature").applyMode()).isEqualTo(ApplyMode.LIVE);
        assertThat(schema.require("ollama.model").applyMode()).isEqualTo(ApplyMode.MODEL_RELOAD);
        assertThat(schema.require("rag.chunk-size").applyMode()).isEqualTo(ApplyMode.REINDEX);
        assertThat(schema.require("spring.servlet.multipart.max-file-size").applyMode()).isEqualTo(ApplyMode.RESTART);
        assertThat(schema.require("spring.servlet.multipart.max-file-size").readOnly()).isTrue();
        assertThat(schema.require("rag.hybrid.keyword-limit").dependsOn()).isEqualTo("rag.hybrid.enabled");
    }

    @Test
    void reportsEnvironmentAndLocalFileSources() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MockPropertySource("systemEnvironment")
                .withProperty("qdrant.top-k", 9));
        environment.getPropertySources().addFirst(new MockPropertySource(
                "applicationConfig: [file:./application-local.properties]")
                .withProperty("rag.max-history", 12));
        EffectiveSettings effective = effective(environment);
        SettingsService service = new SettingsService(schema, effective,
                new SettingsValidator(schema, effective), environment);

        assertThat(view(service, "qdrant.top-k").source()).isEqualTo("env");
        assertThat(view(service, "rag.max-history").source()).isEqualTo("file");
        assertThat(view(service, "ollama.model").source()).isEqualTo("default");
    }

    @Test
    void correlatesProfileKeysAndDefaultsWithoutInventingSettings() {
        EffectiveSettings effective = effective(new MockEnvironment());
        SettingsService service = new SettingsService(schema, effective,
                new SettingsValidator(schema, effective), new MockEnvironment());

        for (SettingDefinition definition : schema.definitions()) {
            if (definition.key().startsWith("spring.") || definition.key().startsWith("server.")) {
                continue;
            }
            assertThat(service.snapshot().settings()).anyMatch(setting -> setting.key().equals(definition.key()));
        }
        assertThat(schema.definitions()).extracting(SettingDefinition::key)
                .doesNotContain("rag.procedure-top-k", "settings.file");
    }

    @Test
    void failedWriteDoesNotChangeEffectiveSettings() throws IOException {
        Path blocker = tempDir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        Path file = blocker.resolve("settings.json");
        EffectiveSettings effective = effective(new MockEnvironment());
        SettingsValidator validator = new SettingsValidator(schema, effective);
        SettingsFileStore fileStore = new SettingsFileStore(file, effective, schema, validator);
        SettingsService service = new SettingsService(schema, effective, validator,
                new MockEnvironment(), fileStore);

        assertThatThrownBy(() -> service.update(Map.of("qdrant.top-k", 9), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(effective.integer("qdrant.top-k")).isEqualTo(7);
    }

    @Test
    void rejectsUnsupportedFormatVersionAsCorrupt() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{\"version\":99,\"overrides\":{\"qdrant.top-k\":9}}");
        EffectiveSettings effective = effective(new MockEnvironment());
        new SettingsFileStore(file, effective, schema, new SettingsValidator(schema, effective)).load();

        assertThat(effective.overrides()).isEmpty();
        assertThat(tempDir.resolve("settings.json.bad")).exists();
    }

    @Test
    void rejectsInvalidValueTypesFromFile() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{\"version\":1,\"overrides\":{\"qdrant.top-k\":\"many\"}}");
        EffectiveSettings effective = effective(new MockEnvironment());
        new SettingsFileStore(file, effective, schema, new SettingsValidator(schema, effective)).load();

        assertThat(effective.overrides()).isEmpty();
        assertThat(tempDir.resolve("settings.json.bad")).exists();
    }

    private static SettingsService.SettingView view(SettingsService service, String key) {
        return service.snapshot().settings().stream()
                .filter(setting -> setting.key().equals(key)).findFirst().orElseThrow();
    }

    private static EffectiveSettings effective(MockEnvironment environment) {
        QdrantProperties qdrant = new QdrantProperties("http://localhost:6333", "docs", 1024, "Cosine", 7,
                Duration.ofSeconds(5), "query: ", "passage:");
        OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                "phi3.5:latest", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
        RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
        return new EffectiveSettings(ollama, qdrant, rag, new ChatProperties(0.1, true, "en"),
                new HybridProperties(false, 50, 60), new RerankProperties(false, 25, "", 600),
                new ArchiveProperties("", "archive"), environment);
    }
}
