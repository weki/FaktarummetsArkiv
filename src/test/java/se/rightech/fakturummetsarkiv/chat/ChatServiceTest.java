package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import se.rightech.fakturummetsarkiv.grounding.QueryTranslator;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RetrievalService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final OllamaClient llm = mock(OllamaClient.class);
    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final QueryTranslator queryTranslator = mock(QueryTranslator.class);
    private final SettingsStore settingsStore = mock(SettingsStore.class);
    private final SessionMemory sessionMemory = new SessionMemory();

    private final OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
            "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest", "");
    private final RagProperties rag = new RagProperties(20, 1200, 200, 8000);

    private final ChatService service = new ChatService(llm, ollama, rag, retrievalService,
            queryTranslator, settingsStore, sessionMemory);

    @SuppressWarnings("unchecked")
    private void respondWith(String... tokens) {
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            for (String token : tokens) {
                onToken.accept(token);
            }
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), any());
    }

    @Test
    void streamsSourcesContentAndFinal() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, true, List.of("docs"), null, 5, 0.0, 20));
        when(queryTranslator.augment("hej")).thenReturn("hej");
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any()))
                .thenReturn(List.of(Map.of("title", "dok", "text", "innehall", "score", 0.9)));
        respondWith("<" + "think>fundera<" + "/think>Hej", "!");

        List<Map<String, Object>> sources = new ArrayList<>();
        List<String> content = new ArrayList<>();
        List<String> finals = new ArrayList<>();

        ChatService.StreamResult result = service.streamChat("hej", "s1", content::add, s -> {
            sources.clear();
            sources.addAll(s);
        }, finals::add);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).containsEntry("title", "dok");
        assertThat(String.join("", content)).isEqualTo("Hej!");
        assertThat(finals).containsExactly("Hej!");
        assertThat(result.answer()).isEqualTo("Hej!");
    }

    @Test
    void skipsQueryTranslatorWhenDisabled() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, false, List.of("docs"), null, 5, 0.0, 20));
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any())).thenReturn(List.of());
        respondWith("svar");

        service.streamChat("hej", "s1", token -> {
        }, null, token -> {
        });

        verify(queryTranslator, never()).augment(anyString());
    }

    @Test
    void storesHistoryForSession() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, true, List.of("docs"), null, 5, 0.0, 20));
        when(queryTranslator.augment(anyString())).thenReturn("hej");
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any())).thenReturn(List.of());
        respondWith("svar");

        service.streamChat("hej", "s1", token -> {
        }, null, token -> {
        });

        List<Map<String, String>> history = sessionMemory.history("s1", 20);
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("role", "user").containsEntry("content", "hej");
        assertThat(history.get(1)).containsEntry("role", "assistant").containsEntry("content", "svar");
    }
}
