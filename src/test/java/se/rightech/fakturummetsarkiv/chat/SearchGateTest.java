package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchGateTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String gateModel) {
        return new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                gateModel, "", 16384, 4096);
    }

    @Test
    void needsSearchWhenModelSaysSearch() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("SEARCH");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("What does invoice 42 say?"))
                .isTrue();
    }

    @Test
    void skipsSearchWhenModelSaysDirect() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("DIRECT");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("Hej, hur mår du?"))
                .isFalse();
    }

    @Test
    void defaultsToSearchWhenGateDisabled() {
        assertThat(new SearchGate(client, props("")).needsSearch("vad är OPC UA?")).isTrue();
    }

    @Test
    void skipsSmallTalkWithoutCallingModel() {
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"));

        assertThat(gate.needsSearch("tack")).isFalse();
        assertThat(gate.needsSearch("Hej, hur mår du?")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void searchesShortFollowUpEvenWhenModelSaysDirect() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("DIRECT");
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"));

        SearchGate.Decision decision = gate.decide("och då?", List.of(), "phi3.5:latest");

        assertThat(decision.search()).isTrue();
        assertThat(decision.query()).isEqualTo("och då?");
    }

    @Test
    void passesRecentHistoryToGate() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("OPC UA next steps");
        SearchGate gate = new SearchGate(client, props("phi3.5:latest"));

        gate.decide("och då?", List.of(
                java.util.Map.of("role", "user", "content", "vad är OPC UA?"),
                java.util.Map.of("role", "assistant", "content", "OPC UA is a protocol.")),
                "phi3.5:latest");

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).chat(
                org.mockito.ArgumentMatchers.eq("phi3.5:latest"),
                anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("RECENT CONVERSATION")
                .contains("vad är OPC UA?")
                .contains("och då?");
    }

    @Test
    void searchesWhenModelGivesUnexpectedOutput() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("maybe");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void searchesWhenGateFails() {
        when(client.chat(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("nere"));
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void searchesWhenGateReturnsBlank() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("  \n ");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void skipsSearchOnlyForLeadingDirect() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("DIRECT");
        assertThat(new SearchGate(client, props("phi3.5:latest")).decide("hej").search()).isFalse();
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("OPC UA overview");
        assertThat(new SearchGate(client, props("phi3.5:latest")).decide("vad är OPC UA?").query())
                .isEqualTo("OPC UA overview");
    }

    @Test
    void searchesWhenFirstWordIsNotADirective() {
        when(client.chat(anyString(), anyString(), anyString()))
                .thenReturn("This does not require a direct lookup; SEARCH");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("vad ar OPC UA?")).isTrue();
    }

    @Test
    void skipsSearchWhenDirectIsTheFirstWord() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("DIRECT\n\nexplanation follows");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("hej")).isFalse();
    }
}
