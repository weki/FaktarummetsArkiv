package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchGateTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String gateModel) {
        return new OllamaProperties("http://localhost:11434", "gemma4:latest",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                gateModel, "");
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
        assertThat(new SearchGate(client, props("")).needsSearch("Hej")).isTrue();
    }

    @Test
    void defaultsToDirectOnUnexpectedOutput() {
        when(client.chat(anyString(), anyString(), anyString())).thenReturn("maybe");
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("Hej")).isFalse();
    }

    @Test
    void defaultsToDirectWhenGateFails() {
        when(client.chat(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("nere"));
        assertThat(new SearchGate(client, props("phi3.5:latest")).needsSearch("Hej")).isFalse();
    }

    @Test
    void directWins() {
        assertThat(SearchGate.isDirect("DIRECT")).isTrue();
        assertThat(SearchGate.isDirect("SEARCH")).isFalse();
        assertThat(SearchGate.isDirect("")).isTrue();
        assertThat(SearchGate.isDirect(null)).isTrue();
        assertThat(SearchGate.isDirect("this is small talk")).isTrue();
    }
}
