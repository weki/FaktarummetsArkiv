package se.rightech.fakturummetsarkiv.grounding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

class GroundingValidatorTest {

    private static final String MODEL = "phi3.5:latest";

    private final OllamaClient client = mock(OllamaClient.class);
    private final OllamaProperties properties = new OllamaProperties(
            "http://localhost:11434", "gemma4:latest", "qllama/multilingual-e5-large",
            java.time.Duration.ofSeconds(5), "phi3.5:latest", true, "", "", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
    private final RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");
    private final GroundingValidator validator = new GroundingValidator(client, properties, rag);

    private static List<Map<String, Object>> sources() {
        return List.of(Map.of("title", "dok", "text", "innehall"));
    }

    @Test
    void reportsSupported() {
        when(client.chat(eq("phi3.5:latest"), any(), any(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("{\"supported\": true, \"reason\": \"ok\"}");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.supported()).isTrue();
        assertThat(result.reason()).isEqualTo("ok");
    }

    @Test
    void parsesClaimLevelVerdict() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn(
                "{\"verdict\":\"PARTIALLY_SUPPORTED\",\"supported\":false,\"reason\":\"ett pastaende saknas\","
                        + "\"unsupportedClaims\":[\"Pastaende X\",\"Pastaende Y\"]}");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.verdict()).isEqualTo(GroundingResult.PARTIALLY_SUPPORTED);
        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedClaims()).containsExactly("Pastaende X", "Pastaende Y");
    }

    @Test
    void derivesVerdictFromLegacySupportedField() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("{\"supported\": true, \"reason\": \"ok\"}");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.verdict()).isEqualTo(GroundingResult.SUPPORTED);
        assertThat(result.unsupportedClaims()).isEmpty();
    }

    @Test
    void reportsUnsupportedWithSurroundingText() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("har ar svaret: {\"supported\": false, \"reason\": \"pastaende X saknas\"} klart");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.supported()).isFalse();
        assertThat(result.reason()).contains("pastaende X");
    }

    @Test
    void handlesBrokenJson() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("ingen json har inte");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.supported()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void prefersLastJsonObjectWhenModelEchoesFormat() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn(
                "{\"supported\": true, \"reason\": \"short explanation\"} "
                        + "{\"supported\": false, \"reason\": \"pastaende X saknas\"}");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.supported()).isFalse();
        assertThat(result.reason()).contains("pastaende X");
    }

    @Test
    void acceptsStringBoolean() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("{\"supported\": \"true\", \"reason\": \"stammer\"}");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.supported()).isTrue();
        assertThat(result.reason()).isEqualTo("stammer");
    }

    @Test
    void acceptsLooseKeyValueFormat() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("supported: true, reason: 'det stammer'");

        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, true);

        assertThat(result.supported()).isTrue();
        assertThat(result.reason()).isEqualTo("det stammer");
    }

    @Test
    void skipsWhenDisabled() {
        GroundingResult result = validator.validate("fraga", sources(), "svar", MODEL, false);

        assertThat(result.supported()).isNull();
    }

    @Test
    void skipsWhenNoModelSelected() {
        GroundingResult result = validator.validate("fraga", sources(), "svar", "", true);

        assertThat(result.supported()).isNull();
    }

    @Test
    void skipsWithoutSources() {
        GroundingResult result = validator.validate("fraga", List.of(), "svar", MODEL, true);

        assertThat(result.supported()).isNull();
    }

    @Test
    void forwardsValidatorThinkingSetting() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("{\"supported\": true, \"reason\": \"ok\"}");
        OllamaProperties thinking = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "e5", java.time.Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b", "qwen3:8b", "qwen3:8b",
                16384, 8192, 2048, 2048, true, false, false, false, false, null);

        new GroundingValidator(client, thinking, rag).validate("fraga", sources(), "svar", "qwen3:8b", true);

        org.mockito.ArgumentCaptor<Boolean> flag = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(client).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                flag.capture());
        assertThat(flag.getValue()).isTrue();
    }

    @Test
    void usesValidatorContextSize() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("{\"supported\": true, \"reason\": \"ok\"}");

        validator.validate("fraga", sources(), "svar", MODEL, true);

        org.mockito.ArgumentCaptor<Integer> numCtx = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(client).chat(anyString(), anyString(), anyString(), anyDouble(),
                numCtx.capture(), anyBoolean());
        assertThat(numCtx.getValue()).isEqualTo(4096);
    }
}
