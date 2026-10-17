package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.util.List;
import java.util.Map;

@Service
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final int MAX_HISTORY_CHARS = 4000;

    private static final String SYSTEM_PROMPT = """
            Given the conversation history, rewrite the user's latest message into a single, fully
            self-contained and specific search query for a document search engine.
            Include all relevant keywords and any missing subjects (such as product names or technical
            terms) that are implied by the conversation, for example by resolving pronouns like "it",
            "that" or "det" to the thing they refer to.
            Keep technical terms, product names and abbreviations unchanged.
            Preserve the language of the latest message.
            Reply with ONLY the rewritten query: no explanations, no quotes, no bullet points, no numbering.
            If the latest message is already self-contained, return it unchanged.
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;

    public QueryRewriter(OllamaClient client, OllamaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String rewrite(String question, List<Map<String, String>> history, String chatModel) {
        if (question == null || question.isBlank() || history == null || history.isEmpty()) {
            return question;
        }
        String model = pickModel(chatModel);
        if (model.isBlank()) {
            return question;
        }
        try {
            String raw = client.chat(model, SYSTEM_PROMPT, buildPrompt(history, question));
            String cleaned = clean(raw);
            if (cleaned.isEmpty() || cleaned.equalsIgnoreCase(question.trim())) {
                return question;
            }
            return cleaned;
        } catch (RuntimeException ex) {
            log.warn("Kunde inte skriva om sökfrågan, använder originalet: {}", ex.getMessage());
            return question;
        }
    }

    String pickModel(String chatModel) {
        if (properties.rewriteModel() != null && !properties.rewriteModel().isBlank()) {
            return properties.rewriteModel();
        }
        if (chatModel != null && !chatModel.isBlank()) {
            return chatModel;
        }
        return properties.model() == null ? "" : properties.model();
    }

    static String buildPrompt(List<Map<String, String>> history, String question) {
        StringBuilder transcript = new StringBuilder();
        for (Map<String, String> message : history) {
            String role = "assistant".equals(message.get("role")) ? "assistant" : "user";
            String content = message.getOrDefault("content", "");
            transcript.append(role).append(": ").append(content).append('\n');
        }
        String text = transcript.toString();
        if (text.length() > MAX_HISTORY_CHARS) {
            text = text.substring(text.length() - MAX_HISTORY_CHARS);
        }
        return "CONVERSATION HISTORY:\n" + text
                + "\nLATEST USER MESSAGE:\n" + question
                + "\n\nSTANDALONE SEARCH QUERY:\n";
    }

    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String t = stripThink(raw).trim();
        for (String line : t.split("\\r?\\n")) {
            String candidate = line.trim();
            if (!candidate.isEmpty()) {
                t = candidate;
                break;
            }
        }
        t = t.replaceAll("(?i)^(standalone\\s+search\\s+query|search\\s+query|query)\\s*[:\\-]\\s*", "");
        t = t.trim();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String stripThink(String s) {
        String t = s;
        int open = t.indexOf("<think");
        if (open >= 0) {
            int gt = t.indexOf('>', open);
            int end = t.indexOf("</think", gt >= 0 ? gt : open);
            if (end >= 0) {
                int endGt = t.indexOf('>', end);
                t = endGt >= 0 ? t.substring(0, open) + t.substring(endGt + 1) : t.substring(0, open);
            } else {
                t = t.substring(0, open);
            }
        }
        return t;
    }
}
