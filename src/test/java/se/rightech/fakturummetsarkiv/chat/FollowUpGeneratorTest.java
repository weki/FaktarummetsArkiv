package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.time.Duration;
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

class FollowUpGeneratorTest {

    private final OllamaClient client = mock(OllamaClient.class);

    private static OllamaProperties props(String refineModel) {
        return new OllamaProperties("http://localhost:11434", "deepseek-r1:14b",
                "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, refineModel, "", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
    }

    private static List<Map<String, Object>> sources() {
        return List.of(Map.of("title", "OPCUA_v15.pdf", "text", "OPC UA uses redundancy."));
    }

    @Test
    void generatesCleanedQuestions() {
        when(client.chat(eq("gemma4:latest"), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("""
                1. How does redundancy work?
                - What are the security requirements
                2. Which systems support OPC UA?
                """);
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        List<String> questions = generator.generate("Fraga", "OPC UA is redundant.", sources(), null, null, 3);

        assertThat(questions).containsExactly(
                "How does redundancy work?",
                "What are the security requirements?",
                "Which systems support OPC UA?");
    }

    @Test
    void refusesWhenModelSaysNone() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("NONE");
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        assertThat(generator.generate("Fraga", "svar", sources(), null, null, 3)).isEmpty();
    }

    @Test
    void returnsEmptyWithoutSourcesOrAnswer() {
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        assertThat(generator.generate("Fraga", "svar", List.of(), null, null, 3)).isEmpty();
        assertThat(generator.generate("Fraga", "", sources(), null, null, 3)).isEmpty();
    }

    @Test
    void returnsEmptyOnError() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenThrow(new RuntimeException("nere"));
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        assertThat(generator.generate("Fraga", "svar", sources(), null, null, 3)).isEmpty();
    }

    @Test
    void unansweredRefusalFallsBackToLocatorQuestion() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("NONE");
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        List<String> questions = generator.generate("Vad är redundant?",
                "I cannot find that information in the documents.", sources(), null, null, 3);

        assertThat(questions).containsExactly("What does OPCUA_v15.pdf say about Vad är redundant?");
    }

    @Test
    void unansweredAnswerIsMarkedInPrompt() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("""
                1. Which section of OPCUA_v15.pdf covers redundancy?
                2. What does OPCUA_v15.pdf say about security?
                """);
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        List<String> questions = generator.generate("Vad är redundant?", "Kunde inte hitta informationen.",
                sources(), null, null, 3);

        assertThat(questions).containsExactly(
                "Which section of OPCUA_v15.pdf covers redundancy?",
                "What does OPCUA_v15.pdf say about security?");
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).chat(eq("gemma4:latest"), any(), prompt.capture(), anyDouble(), anyInt(), anyBoolean());
        assertThat(prompt.getValue())
                .contains("ANSWER STATUS: UNANSWERED")
                .contains("Kunde inte hitta informationen.");
    }

    @Test
    void truncatesLongQuestions() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn(
                "How does the redundancy mechanism in OPC UA handle failover between redundant servers in a "
                        + "multi-site deployment with high availability requirements?");
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        List<String> questions = generator.generate("Fraga", "OPC UA is redundant.", sources(), null, null, 3);

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0)).endsWith("?").hasSizeLessThanOrEqualTo(111);
    }

    @Test
    void dropsDuplicateOfOriginalQuestion() {
        when(client.chat(any(), any(), any(), anyDouble(), anyInt(), anyBoolean())).thenReturn("""
                How does redundancy work?
                How does redundancy work
                Which systems support OPC UA?
                """);
        FollowUpGenerator generator = new FollowUpGenerator(client, props("gemma4:latest"));

        List<String> questions = generator.generate("How does redundancy work?",
                "OPC UA is redundant.", sources(), null, null, 3);

        assertThat(questions).containsExactly(
                "Which systems support OPC UA?");
    }

    @Test
    void forwardsFollowUpThinkingSetting() {
        when(client.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("How does redundancy work?");
        OllamaProperties thinking = new OllamaProperties("http://localhost:11434", "qwen3:8b",
                "e5", Duration.ofSeconds(5), "qwen3:8b", true, "qwen3:8b", "qwen3:8b", "qwen3:8b",
                16384, 8192, 2048, 2048, false, false, false, false, true, null);

        new FollowUpGenerator(client, thinking).generate("Fraga", "svar", sources(), "qwen3:8b", "qwen3:8b", 1);

        org.mockito.ArgumentCaptor<Boolean> flag = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(client).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                flag.capture());
        assertThat(flag.getValue()).isTrue();
    }
}
