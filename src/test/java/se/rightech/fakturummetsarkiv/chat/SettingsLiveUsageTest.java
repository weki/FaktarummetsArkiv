package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.Chunker;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SettingsLiveUsageTest {

    private static final OllamaProperties OLLAMA = new OllamaProperties(
            "http://localhost:11434", "gemma4:latest", "embed-model", Duration.ofSeconds(30),
            "validator", true, "", "phi3.5:latest", "phi3.5:latest", 16384, 8192, 2048, 2048,
            false, false, false, false, false, null);
    private static final RagProperties RAG = new RagProperties(
            20, 1200, 200, 8000, "en", 0.3, 4000, "model");
    private static final ChatProperties CHAT = new ChatProperties(0.1, true, "en");

    @Test
    void chunkerUsesOverrideForNewDocuments() {
        EffectiveSettings effective = EffectiveSettings.baseline(OLLAMA, RAG, CHAT);
        effective.putOverride("rag.chunk-size", 10);
        effective.putOverride("rag.chunk-overlap", 0);

        var chunks = new Chunker(RAG, effective).chunk("abcdefghijklmnopqrstuvwxyz");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(chunk -> chunk.text().length() <= 10);
        assertThat(chunks.size()).isGreaterThan(2);
    }

    @Test
    void searchGateUsesOverrideForMode() {
        EffectiveSettings effective = EffectiveSettings.baseline(OLLAMA, RAG, CHAT);
        effective.putOverride("rag.gate.mode", "off");

        SearchGate gate = new SearchGate(mock(OllamaClient.class), OLLAMA, RAG, effective);

        assertThat(gate.decide("vad är OPC UA?").search()).isTrue();
    }
}
