package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
            Write the query in the SEARCH LANGUAGE specified below, regardless of the conversation language.
            Reply with ONLY the rewritten query: no explanations, no quotes, no bullet points, no numbering.
            If the latest message is already self-contained, return it unchanged.
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;
    private final EffectiveSettings effective;

    @Autowired
    public QueryRewriter(OllamaClient client, OllamaProperties properties, EffectiveSettings effective) {
        this.client = client;
        this.properties = properties;
        this.effective = effective;
    }

    public QueryRewriter(OllamaClient client, OllamaProperties properties) {
        this(client, properties, EffectiveSettings.baseline(properties, new se.rightech.fakturummetsarkiv.rag.RagProperties(
                20, 1200, 200, 8000, "en", 0.3, 4000, "model")));
    }

    public String rewrite(String question, List<Map<String, String>> history, String chatModel) {
        return rewrite(question, history, chatModel, "en");
    }

    public String rewrite(String question, List<Map<String, String>> history, String chatModel,
                          String searchLanguage) {
        if (question == null || question.isBlank()) {
            return question;
        }
        List<Map<String, String>> safeHistory = history == null ? List.of() : history;
        String model = pickModel(chatModel);
        if (model.isBlank()) {
            return question;
        }
        try {
            String raw = client.chat(model, SYSTEM_PROMPT,
                    buildPrompt(safeHistory, question, searchLanguage), 0.0, effective.integer("ollama.rewrite-num-ctx"),
                    effective.bool("ollama.rewrite-thinking"));
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

    String pickModel(String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if (effective.string("ollama.rewrite-model") != null && !effective.string("ollama.rewrite-model").isBlank()) {
            return effective.string("ollama.rewrite-model");
        }
        return effective.string("ollama.model") == null ? "" : effective.string("ollama.model");
    }

    static String buildPrompt(List<Map<String, String>> history, String question) {
        return buildPrompt(history, question, "en");
    }

    /** Full prompt inklusive systemprompt, används för att verifiera kontextbudgeten. */
    static String fullPrompt(List<Map<String, String>> history, String question, String searchLanguage) {
        return SYSTEM_PROMPT + "\n\n" + buildPrompt(history, question, searchLanguage);
    }

    static String buildPrompt(List<Map<String, String>> history, String question, String searchLanguage) {
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
        return "SEARCH LANGUAGE: " + (searchLanguage == null || searchLanguage.isBlank()
                ? "en" : searchLanguage)
                + "\n\nCONVERSATION HISTORY:\n" + text
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
