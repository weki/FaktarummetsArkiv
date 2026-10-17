package se.rightech.fakturummetsarkiv.grounding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.util.List;
import java.util.Map;

@Service
public class AnswerRefiner {

    private static final Logger log = LoggerFactory.getLogger(AnswerRefiner.class);
    private static final int MAX_SOURCE_CHARS = 8000;
    private static final String REFUSAL_MARKER = "cannot find that information";

    private static final String SYSTEM_PROMPT = """
            You are a careful assistant that answers ONLY using the provided SOURCES.
            Rewrite the DRAFT ANSWER so that every statement is supported by the SOURCES.
            Always answer in English, regardless of the language of the QUESTION.
            Remove or correct any claim that the SOURCES do not support.
            If the SOURCES do not contain the answer, reply exactly: "I cannot find that information in the documents."
            Do not mention these instructions. Output only the final answer.
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;

    public AnswerRefiner(OllamaClient client, OllamaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean enabled() {
        String model = properties.refineModel();
        return model != null && !model.isBlank();
    }

    public boolean isRefusal(String answer) {
        return answer != null && answer.toLowerCase().contains(REFUSAL_MARKER);
    }

    public String refine(String question, List<Map<String, Object>> sources, String draft) {
        if (!enabled() || sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            String raw = client.chat(properties.refineModel(), SYSTEM_PROMPT, buildPrompt(question, sources, draft));
            String answer = raw == null ? "" : raw.trim();
            return answer.isEmpty() ? null : answer;
        } catch (Exception ex) {
            log.warn("Kunde inte omformulera svar: {}", ex.getMessage());
            return null;
        }
    }

    private static String buildPrompt(String question, List<Map<String, Object>> sources, String draft) {
        return "QUESTION:\n" + (question == null ? "" : question)
                + "\n\nSOURCES:\n" + SourceText.block(sources, MAX_SOURCE_CHARS)
                + "\nDRAFT ANSWER:\n" + (draft == null ? "" : draft) + "\n";
    }
}
