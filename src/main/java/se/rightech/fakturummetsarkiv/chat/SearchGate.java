package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SearchGate {

    private static final Logger log = LoggerFactory.getLogger(SearchGate.class);
    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_HISTORY_CHARS = 300;

    /** Korta artighetsfraser som aldrig ska utlösa dokumentsökning. */
    private static final Set<String> SMALL_TALK = Set.of(
            "hej", "hej hej", "hejdå", "hej då", "hallå", "tja", "god morgon", "god kväll",
            "hur mår du", "hur är läget", "hej hur mår du",
            "tack", "tack så mycket", "tusen tack", "tackar",
            "ok", "okej", "okay", "bra", "toppen", "nice", "great", "sure",
            "thanks", "thank you", "thanks a lot", "hi", "hi there", "hello", "bye", "goodbye");

    public record Decision(boolean search, String query) {
    }

    private static final String SYSTEM_PROMPT = """
            You are a gatekeeper for a company's internal document archive (invoices, manuals, technical docs).

            Decide if the user's message needs a document search.
            - Reply DIRECT only for clear small talk: greetings, thanks, goodbye or similar social phrases.
            - For EVERYTHING else, including short follow-ups like "and then?" or "vad menar du?", reply with a
              concise search query (3-12 keywords) optimized for semantic retrieval in the archive.
            - Write the search query in the SEARCH LANGUAGE specified below, regardless of the message language.
            - Use the RECENT CONVERSATION to turn follow-ups into a self-contained search query.

            Examples:
            Message: hej                        -> DIRECT
            Message: tack                       -> DIRECT
            Message: vad är OPC UA?              -> OPC UA definition overview
            Message: och då? (after OPC UA)      -> OPC UA next steps
            Message: what does invoice 42 say?   -> invoice 42 content details

            Reply with either DIRECT or an English search query. Nothing else.
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;
    private final RagProperties rag;

    public SearchGate(OllamaClient client, OllamaProperties properties, RagProperties rag) {
        this.client = client;
        this.properties = properties;
        this.rag = rag;
    }

    public boolean enabled() {
        return enabled(properties.gateModel());
    }

    public boolean enabled(String gateModel) {
        return gateModel != null && !gateModel.isBlank();
    }

    public Decision decide(String question) {
        return decide(question, List.of(), properties.gateModel());
    }

    public Decision decide(String question, String gateModel) {
        return decide(question, List.of(), gateModel);
    }

    public Decision decide(String question, List<Map<String, String>> history, String gateModel) {
        return decide(question, history, gateModel, "en");
    }

    public Decision decide(String question, List<Map<String, String>> history, String gateModel,
                           String searchLanguage) {
        if (question == null || question.isBlank()) {
            return new Decision(true, question);
        }
        String mode = gateMode();
        if ("off".equals(mode)) {
            return new Decision(true, question);
        }
        if ("rules".equals(mode)) {
            return new Decision(!isRuleSmallTalk(question), question);
        }
        if (isSmallTalk(question)) {
            return new Decision(false, question);
        }
        if (!enabled(gateModel)) {
            return new Decision(true, question);
        }
        try {
            String raw = client.chat(gateModel, SYSTEM_PROMPT,
                    buildPrompt(question, history, searchLanguage), 0.0, properties.gateNumCtx(),
                    properties.gateThinking()).trim();
            if (raw.isEmpty()) {
                log.warn("Sökgaten gav tomt svar, söker ändå");
                return new Decision(true, question);
            }
            String first = raw.split("\\s+", 2)[0].replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
            if ("DIRECT".equals(first)) {
                // Modellen får bara hoppa över sökning för korta sociala fraser, inte för frågor.
                return new Decision(!isDirectCandidate(question), question);
            }
            String query = raw.replaceAll("\\s+", " ").trim();
            if (query.length() > 200) {
                query = query.substring(0, 200);
            }
            if (query.isBlank()) {
                return new Decision(true, question);
            }
            return new Decision(true, query);
        } catch (RuntimeException ex) {
            log.warn("Kunde inte avgöra sökning, söker ändå: {}", ex.getMessage());
            return new Decision(true, question);
        }
    }

    public boolean needsSearch(String question) {
        return decide(question).search();
    }

    private String gateMode() {
        String mode = rag.gateMode() == null ? "model" : rag.gateMode().trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "model", "rules", "off" -> mode;
            default -> {
                log.warn("Okänt rag.gate.mode '{}', använder model", rag.gateMode());
                yield "model";
            }
        };
    }

    /** Endast korta artighetsfraser utan frågetecken får hoppa över sökning i rules-läget. */
    static boolean isRuleSmallTalk(String question) {
        if (question == null || question.contains("?")) {
            return false;
        }
        String normalized = normalize(question);
        if (normalized.isEmpty() || normalized.split("\\s+").length > 4) {
            return false;
        }
        return SMALL_TALK.contains(normalized);
    }

    static boolean isSmallTalk(String question) {
        return SMALL_TALK.contains(normalize(question));
    }

    /** Kort social fras utan frågetecken, t.ex. en hälsning som inte finns i listan. */
    static boolean isDirectCandidate(String question) {
        if (question == null || question.contains("?")) {
            return false;
        }
        String normalized = normalize(question);
        return !normalized.isEmpty() && normalized.split("\\s+").length <= 4;
    }

    private static String normalize(String question) {
        return question.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String buildPrompt(String question, List<Map<String, String>> history,
                                      String searchLanguage) {
        StringBuilder sb = new StringBuilder();
        sb.append("SEARCH LANGUAGE: ").append(searchLanguage == null || searchLanguage.isBlank()
                ? "en" : searchLanguage).append("\n\n");
        if (history != null && !history.isEmpty()) {
            sb.append("RECENT CONVERSATION:\n");
            int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            for (Map<String, String> message : history.subList(start, history.size())) {
                String role = message.getOrDefault("role", "user");
                String content = message.getOrDefault("content", "");
                if (content.length() > MAX_HISTORY_CHARS) {
                    content = content.substring(0, MAX_HISTORY_CHARS) + "...";
                }
                sb.append(role).append(": ").append(content.replaceAll("\\s+", " ")).append('\n');
            }
            sb.append('\n');
        }
        sb.append("MESSAGE:\n").append(question);
        return sb.toString();
    }
}
