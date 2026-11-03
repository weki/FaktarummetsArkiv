package se.rightech.fakturummetsarkiv.video.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoOllamaClient {

    private static final Logger log = LoggerFactory.getLogger(VideoOllamaClient.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "You are an expert at interpreting user interfaces, screen recordings, and technical flows. "
                    + "Describe the process exactly.";

    private final RestClient restClient;

    @Value("${video.ollama.model:qwen3-vl:8b-instruct}")
    private String model;

    @Value("${video.ollama.num-ctx:16384}")
    private int numCtx;

    @Value("${video.ollama.max-output-tokens:800}")
    private int maxOutputTokens;

    @Value("${video.ollama.max-summary-output-tokens:2500}")
    private int maxSummaryOutputTokens;

    @Value("${video.ollama.repeat-penalty:1.1}")
    private double repeatPenalty;

    @Value("${video.ollama.max-retries:1}")
    private int maxRetries;

    @Value("${video.ollama.max-summary-chars:20000}")
    private int maxSummaryChars;

    @Value("${video.answer-language:English}")
    private String answerLanguage;

    @Value("${video.ollama.keep-alive:0}")
    private String keepAlive;

    @SuppressWarnings("deprecation")
    public VideoOllamaClient(@Value("${video.ollama.base-url:${OLLAMA_BASE_URL:http://localhost:11434}}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(900000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public String getModel() {
        return model;
    }

    public int getMaxSummaryChars() {
        return maxSummaryChars;
    }

    public String analyzeFrames(List<String> base64Images, String prompt) {
        return analyzeFrames(base64Images, prompt, null, null);
    }

    public String analyzeFrames(List<String> base64Images, String prompt, Consumer<String> progress) {
        return analyzeFrames(base64Images, prompt, null, progress);
    }

    public String analyzeFrames(List<String> base64Images, String prompt, Object format, Consumer<String> progress) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        userMessage.put("images", base64Images);
        messages.add(userMessage);

        return chat(messages, base64Images.size(), maxOutputTokens, format, progress);
    }

    public String summarizeAnalyses(List<String> partialAnalyses, String originalPrompt) {
        return summarizeAnalyses(partialAnalyses, originalPrompt, null);
    }

    public String summarizeAnalyses(List<String> partialAnalyses, String originalPrompt, Consumer<String> progress) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here are partial analyses of a screen recording, in chronological order:\n\n");

        int perPartial = Math.max(500, maxSummaryChars / Math.max(1, partialAnalyses.size()));
        for (int i = 0; i < partialAnalyses.size(); i++) {
            String text = partialAnalyses.get(i);
            if (text != null && text.length() > perPartial) {
                text = text.substring(0, perPartial) + "\n[... truncated ...]";
                log.warn("Delanalys {} avkortad till {} tecken för att rymmas i num_ctx.", i + 1, perPartial);
            }
            sb.append("Part ").append(i + 1).append(":\n").append(text).append("\n\n");
        }
        sb.append("Original question: ").append(originalPrompt).append("\n\n");
        sb.append("Summarize the above into an overall picture. Describe step by step what happens ")
                .append("throughout the recording, keeping exact commands, values and timestamps. ")
                .append("Answer in ").append(answerLanguage).append(".");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", sb.toString()));

        return chat(messages, 0, maxSummaryOutputTokens, null, progress);
    }

    private String chat(List<Map<String, Object>> messages, int imageCount, int numPredict,
                        Object format, Consumer<String> progress) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.0);
        options.put("num_ctx", numCtx);
        options.put("repeat_penalty", repeatPenalty);
        if (numPredict > 0) {
            options.put("num_predict", numPredict);
        }

        OllamaChatRequest requestBody = new OllamaChatRequest(
                model,
                true,
                messages,
                options,
                keepAlive,
                false,
                format
        );

        String lastError = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            ChatOutcome outcome = streamOnce(requestBody, imageCount, numPredict, progress);
            if (outcome.content() != null && !outcome.content().isBlank()) {
                return outcome.content();
            }
            lastError = outcome.error() != null ? outcome.error() : "tomt svar från modellen";
            if (attempt <= maxRetries) {
                log.warn("Ollama-anrop misslyckades (försök {} av {}): {}. Försöker igen...",
                        attempt, maxRetries + 1, lastError);
                sleep(2000L * attempt);
            }
        }

        log.error("Ollama-anrop misslyckades efter {} försök: {}", maxRetries + 1, lastError);
        return "Ollama-fel: " + lastError;
    }

    private ChatOutcome streamOnce(OllamaChatRequest requestBody, int imageCount, int numPredict,
                                   Consumer<String> progress) {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String error = null;
        long started = System.currentTimeMillis();
        int lastReported = 0;
        int lastThinkingReported = 0;

        try (InputStream is = restClient.post()
                .uri("/api/chat")
                .body(requestBody)
                .retrieve()
                .body(InputStream.class);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON_MAPPER.readTree(line);

                JsonNode errorNode = node.path("error");
                if (errorNode.isTextual() && !errorNode.asString().isBlank()) {
                    error = errorNode.asString();
                    log.warn("Ollama rapporterade ett fel: {}", error);
                    break;
                }

                JsonNode message = node.path("message");
                String delta = message.path("content").asString("");
                if (!delta.isEmpty()) {
                    content.append(delta);
                }
                String thinkDelta = message.path("thinking").asString("");
                if (!thinkDelta.isEmpty()) {
                    thinking.append(thinkDelta);
                }

                if (progress != null) {
                    if (content.length() - lastReported >= 80) {
                        lastReported = content.length();
                        progress.accept("mottagit " + content.length() + " tecken");
                    } else if (content.length() == 0 && thinking.length() - lastThinkingReported >= 400) {
                        lastThinkingReported = thinking.length();
                        progress.accept("modellen tänker (" + thinking.length() + " tecken)");
                    }
                }

                if (node.path("done").asBoolean(false)) {
                    logStats(node, imageCount, numPredict, content.length(), thinking.length(), started);
                    break;
                }
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Ollama-anrop avbröts: {}", error);
        }

        if (content.length() == 0 && thinking.length() > 0) {
            log.warn("Modellen svarade endast med 'thinking' ({} tecken). Använder det som svar. "
                    + "Höj video.ollama.num-ctx/video.ollama.max-output-tokens om detta återkommer.", thinking.length());
            return new ChatOutcome(cleanThinking(thinking.toString()), null);
        }
        if (content.length() == 0 && error == null) {
            error = "Ollama returnerade inget innehåll";
        }
        return new ChatOutcome(content.toString(), error);
    }

    private void logStats(JsonNode node, int imageCount, int numPredict, int contentChars,
                          int thinkingChars, long started) {
        long promptTokens = node.path("prompt_eval_count").asLong(-1);
        long evalTokens = node.path("eval_count").asLong(-1);
        double seconds = (System.currentTimeMillis() - started) / 1000.0;
        String doneReason = node.path("done_reason").asString("");
        log.info("Ollama: {} bilder, prompt {} tokens, svar {} tokens ({} tecken{}), {} s, orsak '{}'.",
                imageCount, promptTokens, evalTokens, contentChars,
                thinkingChars > 0 ? " + " + thinkingChars + " thinking" : "",
                String.format("%.1f", seconds), doneReason);
        if ("length".equals(doneReason)) {
            if (numPredict > 0) {
                log.warn("Svaret nådde taket för antal tokens (num_predict={}). "
                        + "Höj video.ollama.max-output-tokens om svaret blev avhugget.", numPredict);
            } else {
                log.warn("Svaret nådde slutet av kontextfönstret (num_ctx={}). "
                        + "Höj video.ollama.num-ctx om svaret blev avhugget.", numCtx);
            }
        }
    }

    /** Avlastar videomodellen (keep_alive=0) så att chattmodellen får plats igen. */
    public void unload() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", "");
            body.put("keep_alive", 0);
            restClient.post().uri("/api/generate").body(body).retrieve().toBodilessEntity();
            log.info("Videomodellen {} avlastad.", model);
        } catch (Exception e) {
            log.warn("Kunde inte avlasta videomodellen: {}", e.getMessage());
        }
    }

    private String cleanThinking(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("thinking")) {
            cleaned = cleaned.substring("thinking".length()).strip();
        }
        return cleaned;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ChatOutcome(String content, String error) {
    }
}
