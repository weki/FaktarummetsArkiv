package se.rightech.fakturummetsarkiv.grounding;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryTranslatorTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String translateModel) {
        return new OllamaProperties("http://localhost:11434", "deepseek-r1:14b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest",
                translateModel);
    }

    @Test
    void appendsEnglishTranslationForSwedishQuestion() {
        when(client.chat(eq("gemma4:latest"), any(), any()))
                .thenReturn("how to enable OPC UA in Experion Station");

        String result = new QueryTranslator(client, props("gemma4:latest"))
                .augment("hur enablar jag opcua i experion station");

        assertThat(result).isEqualTo(
                "hur enablar jag opcua i experion station\nhow to enable OPC UA in Experion Station");
    }

    @Test
    void cleansQuotesAndThinking() {
        when(client.chat(eq("gemma4:latest"), any(), any()))
                .thenReturn("\"" + "<" + "think>translate<" + "/think>how to enable OPC UA" + "\"");

        String result = new QueryTranslator(client, props("gemma4:latest")).augment("hur enablar jag opcua");

        assertThat(result).isEqualTo("hur enablar jag opcua\nhow to enable OPC UA");
    }

    @Test
    void normalizesConcatenatedOpcUa() {
        when(client.chat(eq("gemma4:latest"), any(), any()))
                .thenReturn("how do i enable opcua in experion station");

        String result = new QueryTranslator(client, props("gemma4:latest")).augment("hur enablar jag opcua");

        assertThat(result).isEqualTo("hur enablar jag opcua\nhow do i enable OPC UA in experion station");
    }

    @Test
    void leavesEnglishQuestionUntouched() {
        String result = new QueryTranslator(client, props("gemma4:latest")).augment("how do I use Control Builder?");

        assertThat(result).isEqualTo("how do I use Control Builder?");
        verify(client, never()).chat(any(), any(), any());
    }

    @Test
    void disabledReturnsOriginal() {
        String result = new QueryTranslator(client, props("")).augment("hur enablar jag opcua");

        assertThat(result).isEqualTo("hur enablar jag opcua");
        verify(client, never()).chat(any(), any(), any());
    }

    @Test
    void fallsBackToOriginalOnError() {
        when(client.chat(eq("gemma4:latest"), any(), any())).thenThrow(new RuntimeException("nere"));

        String result = new QueryTranslator(client, props("gemma4:latest")).augment("hur enablar jag opcua");

        assertThat(result).isEqualTo("hur enablar jag opcua");
    }

    @Test
    void detectsTranslationNeed() {
        assertThat(QueryTranslator.needsTranslation("hur enablar jag opcua")).isTrue();
        assertThat(QueryTranslator.needsTranslation("vad är OPC UA?")).isTrue();
        assertThat(QueryTranslator.needsTranslation("how to enable OPC UA")).isFalse();
    }
}
