package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.grounding.SourceText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FollowUpGenerator {

    private static final Logger log = LoggerFactory.getLogger(FollowUpGenerator.class);
    private static final int MAX_SOURCE_CHARS = 3000;
    private static final String SYSTEM_PROMPT = """
            You suggest concise follow-up questions for a document question-answering assistant.
            Given the USER QUESTION, the ANSWER and the retrieved SOURCES, write 3 short, specific follow-up questions the user might want to ask next.
            Each question must be answerable from the same documents. Do not invent facts not grounded in the SOURCES.
            Write the questions in English. One question per line. No numbering, no bullets, no quotes, no extra text.
            If the ANSWER is the "cannot find" refusal, reply with exactly: NONE
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;

    public FollowUpGenerator(OllamaClient client, OllamaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public List<String> generate(String question, String answer, List<Map<String, Object>> sources,
                                 String chatModel, int max) {
        if (max <= 0 || answer == null || answer.isBlank() || sources == null || sources.isEmpty()) {
            return List.of();
        }
        String model = pickModel(chatModel);
        if (model.isBlank()) {
            return List.of();
        }
        try {
            String raw = client.chat(model, SYSTEM_PROMPT, buildPrompt(question, answer, sources));
            return parse(raw, max);
        } catch (Exception ex) {
            log.warn("Kunde inte skapa uppföljningsfrågor: {}", ex.getMessage());
            return List.of();
        }
    }

    private String pickModel(String chatModel) {
        if (properties.refineModel() != null && !properties.refineModel().isBlank()) {
            return properties.refineModel();
        }
        if (chatModel != null && !chatModel.isBlank()) {
            return chatModel;
        }
        return properties.model() == null ? "" : properties.model();
    }

    private static String buildPrompt(String question, String answer, List<Map<String, Object>> sources) {
        return "USER QUESTION:\n" + (question == null ? "" : question)
                + "\n\nANSWER:\n" + answer
                + "\n\nSOURCES:\n" + SourceText.block(sources, MAX_SOURCE_CHARS) + "\n";
    }

    private static List<String> parse(String raw, int max) {
        if (raw == null) {
            return List.of();
        }
        String text = raw.trim();
        if (text.equalsIgnoreCase("NONE") || text.isEmpty()) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String q = clean(line);
            if (q.isEmpty()) {
                continue;
            }
            if (!q.endsWith("?")) {
                q = q + "?";
            }
            questions.add(q);
            if (questions.size() >= max) {
                break;
            }
        }
        return List.copyOf(questions);
    }

    private static String clean(String line) {
        String q = line.trim();
        q = q.replaceAll("^[-•*\\s]+", "");
        q = q.replaceAll("^\\d+[.)]\\s*", "");
        q = q.replaceAll("^[\"“”']+|[\"“”'.]+$", "");
        return q.trim();
    }
}
