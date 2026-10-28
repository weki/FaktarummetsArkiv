package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import se.rightech.fakturummetsarkiv.grounding.AnswerRefiner;
import se.rightech.fakturummetsarkiv.grounding.GroundingResult;
import se.rightech.fakturummetsarkiv.grounding.GroundingValidator;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.markdown.MarkdownRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST-kontroller för chattfunktionaliteten.
 * Exponerar endpoints för att strömma svar via SSE, lista modeller, återställa Ollama och uppdatera inställningar.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long STREAM_TIMEOUT_MS = 600_000L;

    private final ChatService chatService;
    private final SettingsStore settingsStore;
    private final SettingsAccessGuard settingsAccessGuard;
    private final GroundingValidator groundingValidator;
    private final AnswerRefiner answerRefiner;
    private final FollowUpGenerator followUpGenerator;
    private final MarkdownRenderer markdownRenderer;
    private final OllamaClient ollamaClient;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public ChatController(ChatService chatService, SettingsStore settingsStore, SettingsAccessGuard settingsAccessGuard,
                          GroundingValidator groundingValidator, AnswerRefiner answerRefiner,
                          FollowUpGenerator followUpGenerator,
                          MarkdownRenderer markdownRenderer, OllamaClient ollamaClient) {
        this.chatService = chatService;
        this.settingsStore = settingsStore;
        this.settingsAccessGuard = settingsAccessGuard;
        this.groundingValidator = groundingValidator;
        this.answerRefiner = answerRefiner;
        this.followUpGenerator = followUpGenerator;
        this.markdownRenderer = markdownRenderer;
        this.ollamaClient = ollamaClient;
    }

    public record ChatRequest(String message, String mode, String sessionId,
                             List<String> collections, List<String> documentIds) {
    }

    @GetMapping("/auth")
    public Map<String, Boolean> auth() {
        return Map.of("authenticated", true);
    }

    @GetMapping("/workspaces")
    public List<Map<String, String>> workspaces() {
        return List.of(Map.of("id", "local", "name", "Lokalt arkiv", "slug", "local"));
    }

    @GetMapping("/settings")
    public WorkspaceSettings settings() {
        return settingsStore.get();
    }

    /**
     * Hämtar lista över tillgängliga Ollama-modeller.
     */
    @GetMapping("/models")
    public List<String> models() {
        return ollamaClient.listModels();
    }

    /**
     * Återställer Ollama: avbryter alla pågående anrop och avlastar modeller.
     * Kräver skrivåtkomst via inställningsskyddet.
     */
    @PostMapping("/ollama/reset")
    public OllamaClient.ResetResult resetOllama(HttpServletRequest request) {
        settingsAccessGuard.requireWriteAccess(request);
        OllamaClient.ResetResult result = ollamaClient.reset();
        log.info("Ollama återställd: {} anrop avbrutna, urladdade {}, misslyckades {}",
                result.cancelledRequests(), result.unloadedModels(), result.failedModels());
        return result;
    }

    /**
     * Uppdaterar arbetsplatsinställningar.
     * Kräver skrivåtkomst via inställningsskyddet.
     */
    @PostMapping("/settings")
    public WorkspaceSettings updateSettings(@RequestBody WorkspaceSettings settings,
                                            HttpServletRequest request) {
        settingsAccessGuard.requireWriteAccess(request);
        return settingsStore.update(settings);
    }

    /**
     * Strömmar chatt-svar via Server-Sent Events (SSE).
     * Öppnar en avbrytbar Ollama-scope, kör ChatService och skickar händelser tills svaret är klart.
     */
    @PostMapping("/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        OllamaClient.Scope scope = ollamaClient.openScope();
        Runnable stopOllama = () -> ollamaClient.cancelScope(scope, "Avbrutet: klienten kopplade ner");
        emitter.onError(error -> stopOllama.run());
        emitter.onTimeout(stopOllama);
        streamExecutor.execute(ollamaClient.within(scope, () -> {
            List<Map<String, Object>> collectedSources = new ArrayList<>();
            try {
                ChatService.StreamResult result = chatService.streamChat(
                        request.message(), request.sessionId(),
                        request.collections(), request.documentIds(),
                        content -> sendEvent(emitter, scope, "content", json(content), MediaType.TEXT_PLAIN),
                        thought -> sendEvent(emitter, scope, "thought", json(thought), MediaType.TEXT_PLAIN),
                        sources -> {
                            collectedSources.clear();
                            collectedSources.addAll(sources);
                            sendEvent(emitter, scope, "sources", sources, MediaType.APPLICATION_JSON);
                        },
                        text -> sendEvent(emitter, scope, "final", json(text), MediaType.TEXT_PLAIN),
                        plan -> sendEvent(emitter, scope, "search", Map.of(
                                "needed", plan.needed(),
                                "query", plan.query() == null ? "" : plan.query()),
                                MediaType.APPLICATION_JSON));

                WorkspaceSettings settings = settingsStore.get();
                Set<Integer> sourceNumbers = sourceNumbers(collectedSources);
                sendEvent(emitter, scope, "answerHtml",
                        json(markdownRenderer.toHtml(result.answer(), sourceNumbers)), MediaType.TEXT_PLAIN);
                sendEvent(emitter, scope, "sources", citingSources(collectedSources, result.answer()),
                        MediaType.APPLICATION_JSON);

                boolean validate = result.searched()
                        && (settings.validateGrounding() == null || settings.validateGrounding());
                CompletableFuture<GroundingResult> validationFuture = validate
                        ? CompletableFuture.supplyAsync(ollamaClient.within(scope, () -> groundingValidator.validate(
                                request.message(), collectedSources, result.answer(),
                                settings.validatorModel(), true)), streamExecutor)
                        : CompletableFuture.completedFuture(null);
                CompletableFuture<List<String>> followUpsFuture = CompletableFuture.supplyAsync(
                        ollamaClient.within(scope, () -> followUpGenerator.generate(
                                request.message(), result.answer(), collectedSources,
                                settings.chatModel(), settings.refineModel(), 3, null, null,
                                settings.answerLanguage())),
                        streamExecutor);

                GroundingResult validation = validationFuture.join();
                if (validation != null) {
                    sendEvent(emitter, scope, "validation", validation, MediaType.APPLICATION_JSON);
                }
                if (validation != null && Boolean.FALSE.equals(validation.supported()) && answerRefiner != null) {
                    String refined = answerRefiner.refine(request.message(), collectedSources,
                            result.answer(), settings.refineModel(), settings.answerLanguage());
                    if (refined != null && !refined.isBlank()
                            && !refined.trim().equals(result.answer().trim())) {
                        sendEvent(emitter, scope, "refined", json(refined), MediaType.TEXT_PLAIN);
                        sendEvent(emitter, scope, "answerHtml",
                                json(markdownRenderer.toHtml(refined, sourceNumbers)), MediaType.TEXT_PLAIN);
                        sendEvent(emitter, scope, "sources", citingSources(collectedSources, refined),
                                MediaType.APPLICATION_JSON);
                        chatService.correctLastAnswer(request.sessionId(), refined);
                    }
                }
                List<String> followUps = followUpsFuture.join();
                sendEvent(emitter, scope, "followups", followUps, MediaType.APPLICATION_JSON);
                sendEvent(emitter, scope, "done", "ok", MediaType.TEXT_PLAIN);
                emitter.complete();
            } catch (Exception ex) {
                log.warn("Chat-strömmen misslyckades", ex);
                sendEvent(emitter, scope, "error",
                        Map.of("message", "Kunde inte slutföra svaret. Försök igen."),
                        MediaType.APPLICATION_JSON);
                emitter.complete();
            } finally {
                ollamaClient.closeScope(scope);
            }
        }));
        return emitter;
    }

    private static Set<Integer> sourceNumbers(List<Map<String, Object>> sources) {
        Set<Integer> numbers = new LinkedHashSet<>();
        for (Map<String, Object> source : sources) {
            Object number = source.get("citationNumber");
            if (number instanceof Number value) {
                numbers.add(value.intValue());
            }
        }
        return numbers;
    }

    private static List<Map<String, Object>> citingSources(List<Map<String, Object>> sources, String answer) {
        Set<Integer> cited = Citations.citedNumbers(answer);
        List<Map<String, Object>> result = new ArrayList<>(sources.size());
        for (Map<String, Object> source : sources) {
            Map<String, Object> copy = new LinkedHashMap<>(source);
            Object number = source.get("citationNumber");
            boolean isCited = number instanceof Number value && cited.contains(value.intValue());
            copy.put("cited", isCited);
            result.add(copy);
        }
        return result;
    }

    /**
     * Skickar en SSE-händelse. Avbryter scopet om klienten kopplar ner eller om strömmen redan är avbruten.
     */
    private void sendEvent(SseEmitter emitter, OllamaClient.Scope scope, String name, Object data, MediaType type) {
        if (scope.isCancelled()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data, type));
        } catch (IOException | IllegalStateException ex) {
            // Webbläsaren är borta; stoppa genereringen för den klienten.
            ollamaClient.cancelScope(scope, "Avbrutet: klienten kopplade ner");
            emitter.completeWithError(ex);
        }
    }

    private static String json(String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (Exception ex) {
            return "\"\"";
        }
    }

    @ExceptionHandler(SettingsAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleForbidden(SettingsAccessDeniedException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleUnavailable(RestClientException ex) {
        log.warn("Ollama eller Qdrant svarade inte", ex);
        return Map.of("error", "Ollama eller Qdrant är inte nåbar");
    }
}
