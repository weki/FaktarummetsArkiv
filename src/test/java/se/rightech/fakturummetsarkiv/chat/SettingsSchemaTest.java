package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsSchemaTest {

    private final SettingsSchema schema = new SettingsSchema();

    @Test
    void everyDefinitionBelongsToAKnownGroupAndHasUniqueKey() {
        Set<String> groupIds = new HashSet<>();
        schema.groups().forEach(group -> groupIds.add(group.id()));
        Set<String> keys = new HashSet<>();

        for (SettingDefinition definition : schema.definitions()) {
            assertThat(groupIds).contains(definition.group());
            assertThat(keys.add(definition.key())).as(definition.key()).isTrue();
            assertThat(definition.applyMode()).isNotNull();
        }
    }

    @Test
    void exposesVerifiedDefaultsAndReadOnlyInfrastructure() {
        assertThat(schema.require("rag.gate.mode").defaultValue()).isEqualTo("model");
        assertThat(schema.require("ollama.chat-num-ctx").defaultValue()).isEqualTo(16384);
        assertThat(schema.require("qdrant.top-k").defaultValue()).isEqualTo(5);
        assertThat(schema.require("qdrant.base-url").editable()).isFalse();
        assertThat(schema.require("ollama.model").editable()).isTrue();
        assertThat(schema.require("ollama.embedding-model").applyMode()).isEqualTo(ApplyMode.REINDEX);
    }

    @Test
    void rejectsUnknownKeys() {
        assertThatThrownBy(() -> schema.require("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
