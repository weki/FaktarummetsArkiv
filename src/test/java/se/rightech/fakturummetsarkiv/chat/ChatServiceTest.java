package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final OllamaClient llm = mock(OllamaClient.class);
    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final SearchGate searchGate = mock(SearchGate.class);
    private final QueryRewriter queryRewriter = mock(QueryRewriter.class);
    private final SettingsStore settingsStore = mock(SettingsStore.class);
    private final SessionMemory sessionMemory = new SessionMemory();

    private final OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
            "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest", "phi3.5:latest", "", 16384, 4096);
    private final RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en");

    private final ChatService service = new ChatService(llm, ollama, rag, retrievalService,
            searchGate, queryRewriter, settingsStore, sessionMemory);

    @BeforeEach
    void defaultRewriteKeepsTheQuestion() {
        when(queryRewriter.rewrite(anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private void respondWith(String... tokens) {
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            for (String token : tokens) {
                onToken.accept(token);
            }
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), any(), any());
    }

    @Test
    void streamsSourcesContentAndFinal() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, false, true, List.of("docs"), null, 5, 0.0, 20, null));
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any(), any()))
                .thenReturn(List.of(Map.of("title", "dok", "text", "innehall", "score", 0.9)));
        when(searchGate.decide(anyString(), any(), any(), any())).thenReturn(new SearchGate.Decision(true, "q"));
        respondWith("<" + "think>fundera<" + "/think>Hej", "!");

        List<Map<String, Object>> sources = new ArrayList<>();
        List<String> content = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();
        List<String> finals = new ArrayList<>();
        List<Boolean> decisions = new ArrayList<>();

        ChatService.StreamResult result = service.streamChat("hej", "s1", List.of(), List.of(), content::add, thoughts::add, s -> {
            sources.clear();
            sources.addAll(s);
        }, finals::add, plan -> decisions.add(plan.needed()));

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).containsEntry("title", "dok");
        assertThat(String.join("", thoughts)).isEqualTo("fundera");
        assertThat(String.join("", content)).isEqualTo("Hej!");
        assertThat(finals).containsExactly("Hej!");
        assertThat(result.answer()).isEqualTo("Hej!");
        assertThat(result.searched()).isTrue();
        assertThat(decisions).containsExactly(true);
    }

    @Test
    void keepsSeparateThinkingOutOfTheAnswer() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, false, true, List.of("docs"), null, 5, 0.0, 20, null));
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onContent = invocation.getArgument(3);
            @SuppressWarnings("unchecked")
            Consumer<String> onThought = invocation.getArgument(4);
            onThought.accept("Thinking Process:\n1. Scan the sources.");
            onContent.accept("The answer is 4.");
            onThought.accept(" Final review done.");
            onContent.accept(" Done.");
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), any(), any());

        List<String> content = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();
        List<String> finals = new ArrayList<>();

        ChatService.StreamResult result = service.streamChat("vad är 2+2", "s1", List.of(), List.of(), content::add, thoughts::add,
                sources -> {
                }, finals::add, plan -> {
        });

        assertThat(result.answer()).isEqualTo("The answer is 4. Done.");
        assertThat(finals).containsExactly("The answer is 4. Done.");
        assertThat(String.join("", thoughts))
                .isEqualTo("Thinking Process:\n1. Scan the sources. Final review done.");
        assertThat(String.join("", content)).isEqualTo("The answer is 4. Done.");
    }

    @Test
    void rewritesFollowUpBeforeSearch() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, false, true, List.of("docs"), null, 5, 0.0, 20, null));
        sessionMemory.add("s1", "Vad är OPC UA?", "OPC UA är ...", 20);
        when(queryRewriter.rewrite(anyString(), any(), anyString(), anyString())).thenReturn("Hur aktiverar jag OPC UA på en server?");
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any(), any())).thenReturn(List.of());
        respondWith("svar");

        service.streamChat("hur aktiverar jag det på en server?", "s1", List.of(), List.of(), token -> {
        }, thought -> {
        }, null, token -> {
        }, null);

        verify(retrievalService).retrieve("Hur aktiverar jag OPC UA på en server?", List.of("docs"), 5, null, null);
    }

    @Test
    void skipsRewriteWhenDisabled() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, false, false, List.of("docs"), null, 5, 0.0, 20, null));
        sessionMemory.add("s1", "Vad är OPC UA?", "OPC UA är ...", 20);
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any(), any())).thenReturn(List.of());
        respondWith("svar");

        service.streamChat("hur aktiverar jag det?", "s1", List.of(), List.of(), token -> {
        }, thought -> {
        }, null, token -> {
        }, null);

        verify(queryRewriter, never()).rewrite(anyString(), any(), anyString(), anyString());
        verify(retrievalService).retrieve("hur aktiverar jag det?", List.of("docs"), 5, null, null);
    }

    @Test
    void answersDirectlyWithoutSearchWhenGateSaysSo() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, true, true, List.of("docs"), null, 5, 0.0, 20, null));
        when(searchGate.decide(eq("hej"), any(), any(), any())).thenReturn(new SearchGate.Decision(false, "hej"));
        when(searchGate.needsSearch("hej")).thenReturn(false);
        respondWith("Hej");

        List<Map<String, Object>> sources = new ArrayList<>();
        List<Boolean> decisions = new ArrayList<>();

        ChatService.StreamResult result = service.streamChat("hej", "s1", List.of(), List.of(), token -> {
        }, thought -> {
        }, s -> {
            sources.clear();
            sources.addAll(s);
        }, token -> {
        }, plan -> decisions.add(plan.needed()));

        assertThat(result.searched()).isFalse();
        assertThat(result.sources()).isEmpty();
        assertThat(sources).isEmpty();
        assertThat(decisions).containsExactly(false);
        verify(retrievalService, never()).retrieve(anyString(), any(), anyInt(), any());
    }

    @Test
    void storesHistoryForSession() {
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, false, true, List.of("docs"), null, 5, 0.0, 20, null));
        when(retrievalService.retrieve(anyString(), any(), anyInt(), any(), any())).thenReturn(List.of());
        respondWith("svar");

        service.streamChat("hej", "s1", List.of(), List.of(), token -> {
        }, thought -> {
        }, null, token -> {
        }, null);

        List<Map<String, String>> history = sessionMemory.history("s1", 20);
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("role", "user").containsEntry("content", "hej");
        assertThat(history.get(1)).containsEntry("role", "assistant").containsEntry("content", "svar");
    }
}
