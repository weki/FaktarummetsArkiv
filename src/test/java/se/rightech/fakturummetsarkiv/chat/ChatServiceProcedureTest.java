package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RetrievalService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tester för procedur-intent-beteende i ChatService.
 * Verifierar att SHOW/NONE/DISCUSS/CREATE/EDIT hanterar procedur-text och LLM-anrop korrekt.
 */
class ChatServiceProcedureTest {

    private final OllamaClient llm = mock(OllamaClient.class);
    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final SearchGate searchGate = mock(SearchGate.class);
    private final QueryRewriter queryRewriter = mock(QueryRewriter.class);
    private final SettingsStore settingsStore = mock(SettingsStore.class);
    private final SessionMemory sessionMemory = new SessionMemory();

    private final OllamaProperties ollama = new OllamaProperties("http://localhost:11434", "gemma4:latest",
            "qllama/multilingual-e5-large", Duration.ofSeconds(5), "phi3.5:latest", true, "gemma4:latest", "phi3.5:latest", "", 16384, 4096, 2048, 2048, false, false, false, false, false, null);
    private final RagProperties rag = new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model");

    private final ChatService service = new ChatService(llm, ollama, rag, retrievalService,
            searchGate, queryRewriter, settingsStore, sessionMemory, new ChatProperties(0.1, true, "en"));

    private static final String VALID_YAML = """
            metadata:
              title: Test Procedure
              author: Test
            sections:
              - title: Purpose
                steps:
                  - command: Do something
            """;

    private static final String INVALID_YAML = """
            invalid: yaml: content
            missing: required fields
            """;

    @BeforeEach
    void setupDefaults() {
        when(queryRewriter.rewrite(anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(settingsStore.get()).thenReturn(new WorkspaceSettings(
                "chat", "gemma4:latest", "phi3.5:latest", true, true, true, List.of("docs"), null, 5, 0.0, 20, null));
    }

    @SuppressWarnings("unchecked")
    private void respondWith(String... tokens) {
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(5);
            for (String token : tokens) {
                onToken.accept(token);
            }
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), anyInt(), anyBoolean(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void respondWithRepair(String firstResponse, String repairedResponse) {
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            Consumer<String> onToken = invocation.getArgument(5);
            String response = call == 1 ? firstResponse : repairedResponse;
            for (String token : response.split("(?<=\\n)")) {
                onToken.accept(token);
            }
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), anyInt(), anyBoolean(), any(), any());

        when(llm.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn("```yaml\n" + repairedResponse + "\n```");
    }

    @Test
    void showDoesNotCallLlm() {
        // SHOW: "Visa YAML" med fäst procedur -> 0 LLM-anrop
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "Visa YAML", SearchGate.Mode.ATTACHMENT));

        List<String> finals = new ArrayList<>();
        AtomicInteger procedureCalls = new AtomicInteger(0);

        ChatService.StreamResult result = service.streamChat(
                "Visa YAML", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, finals::add, plan -> {},
                proc -> procedureCalls.incrementAndGet()
        );

        // Verifiera att LLM inte anropades
        verify(llm, never()).chatStream(anyString(), any(), anyDouble(), anyInt(), anyBoolean(), any(), any());
        verify(llm, never()).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyBoolean());

        // Verifiera att onFinal fick yaml-block
        assertThat(finals).hasSize(1);
        assertThat(finals.get(0)).contains("```yaml");
        assertThat(finals.get(0)).contains(VALID_YAML);

        // Verifiera att onProcedure inte anropades
        assertThat(procedureCalls.get()).isZero();
    }

    @Test
    void discussDoesNotCallOnProcedureEvenIfLlmReturnsYaml() {
        // DISCUSS: fake-LLM svarar med yaml-block, men onProcedure ska inte anropas
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "fråga", SearchGate.Mode.ATTACHMENT));
        respondWith("```yaml\n" + VALID_YAML + "\n```");

        List<String> finals = new ArrayList<>();
        AtomicInteger procedureCalls = new AtomicInteger(0);
        AtomicInteger llmCalls = new AtomicInteger(0);

        doAnswer(invocation -> {
            llmCalls.incrementAndGet();
            Consumer<String> onToken = invocation.getArgument(5);
            onToken.accept("```yaml\n");
            onToken.accept(VALID_YAML);
            onToken.accept("\n```");
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), anyInt(), anyBoolean(), any(), any());

        service.streamChat(
                "Varför steg 3?", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, finals::add, plan -> {},
                proc -> procedureCalls.incrementAndGet()
        );

        // Verifiera att LLM anropades exakt en gång
        assertThat(llmCalls.get()).isEqualTo(1);

        // Verifiera att onProcedure INTE anropades (DISCUSS ska inte trigga procedur-extraktion)
        assertThat(procedureCalls.get()).isZero();
    }

    @Test
    void noneDoesNotCallOnProcedureEvenIfLlmReturnsYaml() {
        // NONE: fake-LLM svarar med yaml-block, men onProcedure ska inte anropas
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "fråga", SearchGate.Mode.ATTACHMENT));

        AtomicInteger procedureCalls = new AtomicInteger(0);
        AtomicInteger llmCalls = new AtomicInteger(0);

        doAnswer(invocation -> {
            llmCalls.incrementAndGet();
            Consumer<String> onToken = invocation.getArgument(5);
            onToken.accept("```yaml\n");
            onToken.accept(VALID_YAML);
            onToken.accept("\n```");
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), anyInt(), anyBoolean(), any(), any());

        service.streamChat(
                "hej", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, token -> {}, plan -> {},
                proc -> procedureCalls.incrementAndGet()
        );

        assertThat(llmCalls.get()).isEqualTo(1);
        assertThat(procedureCalls.get()).isZero();
    }

    @Test
    void createWithValidYamlCallsOnProcedure() {
        // CREATE med giltig YAML -> onProcedure(valid=true), 1 LLM-anrop
        // Vi skickar in procedureYaml för att sätta hasProcedure=true
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "skapa", SearchGate.Mode.ATTACHMENT));
        respondWith("Här är proceduren:\n```yaml\n" + VALID_YAML + "\n```");

        List<Map<String, Object>> procedures = new ArrayList<>();
        AtomicInteger llmCalls = new AtomicInteger(0);

        doAnswer(invocation -> {
            llmCalls.incrementAndGet();
            Consumer<String> onToken = invocation.getArgument(5);
            onToken.accept("Här är proceduren:\n```yaml\n");
            onToken.accept(VALID_YAML);
            onToken.accept("\n```");
            return null;
        }).when(llm).chatStream(anyString(), any(), anyDouble(), anyInt(), anyBoolean(), any(), any());

        // Skicka in en existerande procedur så att hasProcedure=true
        service.streamChat(
                "Skapa en procedur", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, token -> {}, plan -> {},
                procedures::add
        );

        assertThat(llmCalls.get()).isEqualTo(1);
        assertThat(procedures).hasSize(1);
        assertThat(procedures.get(0)).containsEntry("valid", true);
        assertThat(procedures.get(0)).containsKey("yaml");
    }

    @Test
    void discussPromptDoesNotContainSchema() {
        // Verifiera att DISCUSS-prompten inte innehåller "SCHEMA" eller "write ONE fenced"
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "fråga", SearchGate.Mode.ATTACHMENT));
        respondWith("Svar om proceduren.");

        ArgumentCaptor<List<Map<String, String>>> messagesCaptor = ArgumentCaptor.forClass(List.class);

        service.streamChat(
                "Varför steg 3?", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, token -> {}, plan -> {},
                proc -> {}
        );

        verify(llm).chatStream(anyString(), messagesCaptor.capture(), anyDouble(), anyInt(), anyBoolean(), any(), any());
        List<Map<String, String>> messages = messagesCaptor.getValue();
        String systemPrompt = messages.get(0).get("content");

        assertThat(systemPrompt).doesNotContain("SCHEMA");
        assertThat(systemPrompt).doesNotContain("write ONE fenced");
        assertThat(systemPrompt).contains("CURRENT PROCEDURE");
    }

    @Test
    void createPromptContainsSchemaAndFrames() {
        // Verifiera att CREATE-prompten innehåller schema och AVAILABLE FRAMES
        // Vi skickar in procedureYaml för att trigga CREATE (hasProcedure=true)
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "skapa", SearchGate.Mode.ATTACHMENT));
        respondWith("```yaml\n" + VALID_YAML + "\n```");

        ArgumentCaptor<List<Map<String, String>>> messagesCaptor = ArgumentCaptor.forClass(List.class);

        // "Skapa proceduren på nytt" med procedureYaml -> CREATE
        service.streamChat(
                "Skapa proceduren på nytt", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, token -> {}, plan -> {},
                proc -> {}
        );

        verify(llm).chatStream(anyString(), messagesCaptor.capture(), anyDouble(), anyInt(), anyBoolean(), any(), any());
        List<Map<String, String>> messages = messagesCaptor.getValue();
        String systemPrompt = messages.get(0).get("content");

        // CREATE-prompt ska innehålla schema (från procedureSchemaMinified)
        assertThat(systemPrompt).contains("SCHEMA");
        assertThat(systemPrompt).contains("PROCEDURE MODE");
    }

    @Test
    void confirmationWithProposalMarkerTriggersEdit() {
        // "ja, gör det" efter svar med PROPOSAL_MARKER -> EDIT
        when(searchGate.decide(anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new SearchGate.Decision(true, "ja", SearchGate.Mode.ATTACHMENT));

        // Simulera att senaste svaret innehåller PROPOSAL_MARKER
        sessionMemory.add("s1", "Tidigare fråga", "Här är ett förslag. Say the word and I'll apply it", 20);

        respondWith("```yaml\n" + VALID_YAML + "\n```");

        AtomicInteger procedureCalls = new AtomicInteger(0);

        service.streamChat(
                "ja, gör det", "s1", List.of(), List.of(), List.of(), VALID_YAML,
                token -> {}, thought -> {}, sources -> {}, token -> {}, plan -> {},
                proc -> procedureCalls.incrementAndGet()
        );

        // EDIT ska trigga procedur-extraktion
        assertThat(procedureCalls.get()).isGreaterThan(0);
    }
}
