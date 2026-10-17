package se.rightech.fakturummetsarkiv.chat;

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
import se.rightech.fakturummetsarkiv.grounding.GroundingResult;
import se.rightech.fakturummetsarkiv.grounding.GroundingValidator;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long STREAM_TIMEOUT_MS = 600_000L;

    private final ChatService chatService;
    private final SettingsStore settingsStore;
    private final GroundingValidator groundingValidator;
    private final OllamaClient ollamaClient;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public ChatController(ChatService chatService, SettingsStore settingsStore,
                          GroundingValidator groundingValidator, OllamaClient ollamaClient) {
        this.chatService = chatService;
        this.settingsStore = settingsStore;
        this.groundingValidator = groundingValidator;
        this.ollamaClient = ollamaClient;
    }

    public record ChatRequest(String message, String mode, String sessionId) {
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

    @GetMapping("/models")
    public List<String> models() {
        return ollamaClient.listModels();
    }

    @PostMapping("/settings")
    public WorkspaceSettings updateSettings(@RequestBody WorkspaceSettings settings) {
        return settingsStore.update(settings);
    }

    @PostMapping("/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            List<Map<String, Object>> collectedSources = new ArrayList<>();
            try {
                ChatService.StreamResult result = chatService.streamChat(
                        request.message(), request.sessionId(),
                        content -> sendEvent(emitter, "content", json(content), MediaType.TEXT_PLAIN),
                        sources -> {
                            collectedSources.clear();
                            collectedSources.addAll(sources);
                            sendEvent(emitter, "sources", sources, MediaType.APPLICATION_JSON);
                        },
                        text -> sendEvent(emitter, "final", json(text), MediaType.TEXT_PLAIN));

                WorkspaceSettings settings = settingsStore.get();
                boolean validate = settings.validateGrounding() == null || settings.validateGrounding();
                GroundingResult validation = groundingValidator.validate(
                        request.message(), collectedSources, result.answer(),
                        settings.validatorModel(), validate);
                sendEvent(emitter, "validation", validation, MediaType.APPLICATION_JSON);
                sendEvent(emitter, "done", "ok", MediaType.TEXT_PLAIN);
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data, MediaType type) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, type));
        } catch (IOException | IllegalStateException ex) {
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

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleUnavailable(RestClientException ex) {
        return Map.of("error", "Ollama eller Qdrant är inte nåbar", "detail", ex.getMessage());
    }
}
