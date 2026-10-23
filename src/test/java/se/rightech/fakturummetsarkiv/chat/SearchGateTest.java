package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchGateTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static RagProperties rag(String mode) {
        return new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, mode);
    }

    private static RagProperties rag() {
        return rag("model");
    }

    private static OllamaProperties props(String gateModel) {
        return new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                gateModel, "", 16384, 4096, false, false, false, false, false, null);
    }

    @Test
    void needsSearchWhenModelSaysSearch() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("SEARCH");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("What does invoice 42 say?"))
                .isTrue();
    }

    @Test
    void skipsSearchWhenModelSaysDirect() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("DIRECT");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("Hej, hur mår du?"))
                .isFalse();
    }

    @Test
    void defaultsToSearchWhenGateDisabled() {
        assertThat(new SearchGate(client, props(""), rag()).needsSearch("vad är OPC UA?")).isTrue();
    }

    @Test
    void skipsSmallTalkWithoutCallingModel() {
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"), rag());

        assertThat(gate.needsSearch("tack")).isFalse();
        assertThat(gate.needsSearch("Hej, hur mår du?")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void searchesShortFollowUpEvenWhenModelSaysDirect() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("DIRECT");
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"), rag());

        SearchGate.Decision decision = gate.decide("och då?", List.of(), "phi3.5:latest");

        assertThat(decision.search()).isTrue();
        assertThat(decision.query()).isEqualTo("och då?");
    }

    @Test
    void passesRecentHistoryToGate() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("OPC UA next steps");
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"), rag());

        gate.decide("och då?", List.of(
                java.util.Map.of("role", "user", "content", "vad är OPC UA?"),
                java.util.Map.of("role", "assistant", "content", "OPC UA is a protocol.")),
                "phi3.5:latest");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).chat(
                org.mockito.ArgumentMatchers.eq("phi3.5:latest"),
                anyString(), prompt.capture(), anyDouble(), anyInt(), anyBoolean());
        assertThat(prompt.getValue())
                .contains("RECENT CONVERSATION")
                .contains("SEARCH LANGUAGE: en")
                .contains("vad är OPC UA?")
                .contains("och då?");
    }

    @Test
    void usesConfiguredSearchLanguageForTheQuery() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("OPC UA översikt");
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"), rag());

        gate.decide("vad är OPC UA?", List.of(), "phi3.5:latest", "sv");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).chat(
                org.mockito.ArgumentMatchers.eq("phi3.5:latest"),
                anyString(), prompt.capture(), anyDouble(), anyInt(), anyBoolean());
        assertThat(prompt.getValue()).contains("SEARCH LANGUAGE: sv");
    }

    @Test
    void searchesWhenModelGivesUnexpectedOutput() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("maybe");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void searchesWhenGateFails() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenThrow(new RuntimeException("nere"));
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void searchesWhenGateReturnsBlank() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("  \n ");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void skipsSearchOnlyForLeadingDirect() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("DIRECT");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).decide("hej").search()).isFalse();
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("OPC UA overview");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).decide("vad är OPC UA?").query())
                .isEqualTo("OPC UA overview");
    }

    @Test
    void searchesWhenFirstWordIsNotADirective() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("This does not require a direct lookup; SEARCH");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void skipsSearchWhenDirectIsTheFirstWord() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean())).thenReturn("DIRECT\n\nexplanation follows");
        assertThat(new SearchGate(client, props("phi3.5:latest"), rag()).needsSearch("hej")).isFalse();
    }

    @Test
    void rulesModeSkipsOnlyShortSmallTalkWithoutQuestionMark() {
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"), rag("rules"));

        assertThat(gate.needsSearch("hej")).isFalse();
        assertThat(gate.needsSearch("tack")).isFalse();
        assertThat(gate.needsSearch("ok")).isFalse();
        assertThat(gate.needsSearch("hello")).isFalse();
        assertThat(gate.needsSearch("thanks")).isFalse();
        assertThat(gate.needsSearch("hej hur mår du")).isFalse();
        assertThat(gate.needsSearch("Hej hur mår du?")).isTrue();
        assertThat(gate.needsSearch("och då?")).isTrue();
        assertThat(gate.needsSearch("vad är OPC UA?")).isTrue();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void offModeAlwaysSearchesEvenForSmallTalk() {
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"), rag("off"));

        assertThat(gate.decide("hej").search()).isTrue();
        assertThat(gate.decide("tack").search()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void forwardsGateThinkingSetting() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("OPC UA overview");
        OllamaProperties thinking = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "e5", Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b", "qwen3:8b", "qwen3:8b",
                16384, 8192, false, false, true, false, false, null);

        new SearchGate(client, thinking, rag()).decide("vad är OPC UA?");

        org.mockito.ArgumentCaptor<Boolean> flag = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(client).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                flag.capture());
        assertThat(flag.getValue()).isTrue();
    }
}
