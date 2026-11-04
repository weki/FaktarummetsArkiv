package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    public enum Mode { DIRECT, ATTACHMENT, SEARCH, BOTH }

    private static final Set<String> ARCHIVE_SIGNALS = Set.of(
            "arkivet", "dokumentation", "manual", "enligt", "standard", "krav",
            "finns det", "vad säger", "jämför", "verifiera", "kontrollera mot");
    private static final Set<String> ATTACHMENT_SIGNALS = Set.of(
            "steg", "videon", "inspelningen", "proceduren", "yaml");
    private static final Set<String> PROCEDURE_VERBS = Set.of(
            "skapa", "ändra", "lägg till", "ta bort", "förkorta", "översätt",
            "förtydliga", "skriv om", "yaml", "procedur");

    public record Decision(boolean search, String query, Mode mode) {
        public Decision(boolean search, String query) {
            this(search, query, search ? Mode.SEARCH : Mode.DIRECT);
        }
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
    private final EffectiveSettings effective;

    @Autowired
    public SearchGate(OllamaClient client, OllamaProperties properties, RagProperties rag,
                      EffectiveSettings effective) {
        this.client = client;
        this.properties = properties;
        this.rag = rag;
        this.effective = effective;
    }

    public SearchGate(OllamaClient client, OllamaProperties properties, RagProperties rag) {
        this(client, properties, rag, EffectiveSettings.baseline(properties, rag));
    }

    public boolean enabled() {
        return enabled(effective.effectiveModel("gate"));
    }

    public boolean enabled(String gateModel) {
        return gateModel != null && !gateModel.isBlank();
    }

    public Decision decide(String question) {
        return decide(question, List.of(), effective.effectiveModel("gate"));
    }

    public Decision decide(String question, String gateModel) {
        return decide(question, List.of(), gateModel);
    }

    public Decision decide(String question, List<Map<String, String>> history, String gateModel) {
        return decide(question, history, gateModel, effective.string("rag.search-language"));
    }

    public Decision decide(String question, List<Map<String, String>> history, String gateModel,
                           String searchLanguage) {
        return decide(question, history, gateModel, searchLanguage, null, false);
    }

    public Decision decide(String question, List<Map<String, String>> history, String gateModel,
                           String searchLanguage, String attachmentSummary, boolean hasProcedure) {
        if (question == null || question.isBlank()) {
            return new Decision(true, question, SearchGate.Mode.SEARCH);
        }
        String mode = gateMode();
        boolean hasAttachment = (attachmentSummary != null && !attachmentSummary.isBlank()) || hasProcedure;
        if ("off".equals(mode)) {
            return new Decision(true, question, SearchGate.Mode.SEARCH);
        }
        if ("rules".equals(mode)) {
            if (!hasAttachment) {
                return new Decision(!isRuleSmallTalk(question), question,
                        !isRuleSmallTalk(question) ? SearchGate.Mode.SEARCH : SearchGate.Mode.DIRECT);
            }
            return rulesDecisionWithAttachment(question, attachmentSummary, hasProcedure);
        }
        if (isSmallTalk(question)) {
            return new Decision(false, question, SearchGate.Mode.DIRECT);
        }
        if (!enabled(gateModel)) {
            return new Decision(true, question, SearchGate.Mode.SEARCH);
        }
        try {
            if (!hasAttachment) {
                String raw = client.chat(gateModel, SYSTEM_PROMPT,
                        buildPrompt(question, history, searchLanguage), 0.0, effective.effectiveNumCtx("gate"),
                        effective.bool("ollama.gate-thinking")).trim();
                if (raw.isEmpty()) {
                    log.warn("Sökgaten gav tomt svar, söker ändå");
                    return new Decision(true, question, SearchGate.Mode.SEARCH);
                }
                String first = raw.split("\\s+", 2)[0].replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
                if ("DIRECT".equals(first)) {
                    return new Decision(!isDirectCandidate(question), question,
                            !isDirectCandidate(question) ? SearchGate.Mode.SEARCH : SearchGate.Mode.DIRECT);
                }
                String query = raw.replaceAll("\\s+", " ").trim();
                if (query.length() > 200) {
                    query = query.substring(0, 200);
                }
                if (query.isBlank()) {
                    return new Decision(true, question, SearchGate.Mode.SEARCH);
                }
                return new Decision(true, query, SearchGate.Mode.SEARCH);
            }
            // Attachment-aware path
            String raw = client.chat(gateModel, attachmentSystemPrompt(),
                    buildAttachmentPrompt(question, history, searchLanguage, attachmentSummary, hasProcedure),
                    0.0, effective.effectiveNumCtx("gate"), effective.bool("ollama.gate-thinking")).trim();
            if (raw.isEmpty()) {
                log.warn("Sökgaten gav tomt svar, använder BOTH");
                return new Decision(true, question, parseAttachmentDefault());
            }
            String first = raw.split("\\s+", 2)[0].replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
            SearchGate.Mode parsedMode;
            if ("DIRECT".equals(first)) parsedMode = SearchGate.Mode.DIRECT;
            else if ("ATTACHMENT".equals(first)) parsedMode = SearchGate.Mode.ATTACHMENT;
            else if ("SEARCH".equals(first)) parsedMode = SearchGate.Mode.SEARCH;
            else if ("BOTH".equals(first)) parsedMode = SearchGate.Mode.BOTH;
            else parsedMode = parseAttachmentDefault();
            if (parsedMode == SearchGate.Mode.DIRECT && !isDirectCandidate(question)) {
                parsedMode = hasAttachment ? SearchGate.Mode.ATTACHMENT : SearchGate.Mode.SEARCH;
            }
            boolean search = (parsedMode == SearchGate.Mode.SEARCH || parsedMode == SearchGate.Mode.BOTH);
            String query = question;
            if (search) {
                String rest = raw.length() > first.length() ? raw.substring(first.length()).trim() : "";
                if (rest.startsWith(":")) rest = rest.substring(1).trim();
                if (!rest.isBlank()) query = rest;
                if (query.length() > 200) query = query.substring(0, 200);
            }
            return new Decision(search, query, parsedMode);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("repeat") || msg.contains("token repeat")) {
                log.debug("Gate-modellen hamnade i repetitionsloop, använder regelbaserad beslut: {}", ex.getMessage());
                if (hasAttachment) {
                    return rulesDecisionWithAttachment(question, attachmentSummary, hasProcedure);
                }
                if (isSmallTalk(question)) {
                    return new Decision(false, question, SearchGate.Mode.DIRECT);
                }
                return new Decision(!isRuleSmallTalk(question), question,
                        !isRuleSmallTalk(question) ? SearchGate.Mode.SEARCH : SearchGate.Mode.DIRECT);
            }
            log.warn("Kunde inte avgöra sökning, söker ändå: {}", ex.getMessage());
            if (hasAttachment) {
                return new Decision(true, question, SearchGate.Mode.BOTH);
            }
            return new Decision(true, question, SearchGate.Mode.SEARCH);
        }
    }

    private SearchGate.Mode parseAttachmentDefault() {
        String def = effective.string("rag.gate.attachment-default");
        if (def == null) def = "both";
        def = def.trim().toLowerCase(Locale.ROOT);
        if ("attachment".equals(def)) return SearchGate.Mode.ATTACHMENT;
        if ("search".equals(def)) return SearchGate.Mode.SEARCH;
        if ("direct".equals(def)) return SearchGate.Mode.DIRECT;
        return SearchGate.Mode.BOTH;
    }

    private SearchGate.Decision defaultAttachmentDecision(String question, boolean hasAttachment) {
        return new SearchGate.Decision(true, question, hasAttachment ? SearchGate.Mode.BOTH : SearchGate.Mode.SEARCH);
    }

    private boolean containsAny(String text, Set<String> words) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    private SearchGate.Decision rulesDecisionWithAttachment(String question, String attachmentSummary, boolean hasProcedure) {
        String q = question == null ? "" : question;
        if (isSmallTalk(q)) {
            return new SearchGate.Decision(false, q, SearchGate.Mode.DIRECT);
        }
        boolean archiveSignal = containsAny(q, ARCHIVE_SIGNALS);
        boolean attachmentSignal = containsAny(q, ATTACHMENT_SIGNALS);
        boolean procedureVerb = containsAny(q, PROCEDURE_VERBS);
        if (archiveSignal && (attachmentSignal || hasProcedure)) {
            return new SearchGate.Decision(true, q, SearchGate.Mode.BOTH);
        }
        if (archiveSignal && !attachmentSignal) {
            return new SearchGate.Decision(true, q, SearchGate.Mode.SEARCH);
        }
        if (attachmentSignal || procedureVerb) {
            return new SearchGate.Decision(false, q, SearchGate.Mode.ATTACHMENT);
        }
        boolean ha = (attachmentSummary != null && !attachmentSummary.isBlank()) || hasProcedure;
        return new SearchGate.Decision(true, q, ha ? SearchGate.Mode.BOTH : SearchGate.Mode.SEARCH);
    }

    private String attachmentSystemPrompt() {
        return """
                You are a gatekeeper for a company's internal document archive and an attached video/procedure.

                Decide if the user's message needs a document search, only the attachment, both, or is small talk.
                - Reply DIRECT only for clear small talk: greetings, thanks, goodbye.
                - Reply ATTACHMENT when the question is about the attached video/analysis/procedure (explain, change, translate, shorten, add warning, create YAML, tags) and needs no archive knowledge. Prefer ATTACHMENT over BOTH when in doubt.
                - Reply SEARCH when the question is clearly about facts in the archive and not the attachment.
                - Reply BOTH only when the question explicitly connects the attachment to the archive (compare, verify, find existing docs, check requirements).
                - For SEARCH or BOTH, after the mode word write a colon and a concise search query (3-12 keywords) in the SEARCH LANGUAGE.
                - For DIRECT or ATTACHMENT, write nothing else.

                Examples:
                Message: hej                        -> DIRECT
                Message: Vad händer i videon?       -> ATTACHMENT
                Message: Skapa en YAML-procedur     -> ATTACHMENT
                Message: Vad säger manualen om X?   -> SEARCH
                Message: Stämmer steg 4 med docs?   -> BOTH

                Reply with one of DIRECT, ATTACHMENT, SEARCH, BOTH. Nothing else.
                """;
    }

    private String buildAttachmentPrompt(String question, List<Map<String, String>> history, String searchLanguage,
                                        String attachmentSummary, boolean hasProcedure) {
        StringBuilder sb = new StringBuilder();
        sb.append("SEARCH LANGUAGE: ").append(searchLanguage).append("\n");
        if (attachmentSummary != null && !attachmentSummary.isBlank()) {
            sb.append("ATTACHMENT SUMMARY:\n").append(attachmentSummary).append("\n");
        }
        if (hasProcedure) {
            sb.append("A pinned YAML procedure is attached.\n");
        }
        sb.append("RECENT CONVERSATION:\n");
        if (history != null) {
            int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> m = history.get(i);
                String role = m.getOrDefault("role", "");
                String content = m.getOrDefault("content", "");
                if (content.length() > MAX_HISTORY_CHARS) {
                    content = content.substring(0, MAX_HISTORY_CHARS);
                }
                sb.append(role).append(": ").append(content).append("\n");
            }
        }
        sb.append("Message: ").append(question).append("\n");
        return sb.toString();
    }

    public boolean needsSearch(String question) {
        return decide(question).search();
    }

    private String gateMode() {
        String mode = effective.string("rag.gate.mode") == null ? "model" : effective.string("rag.gate.mode").trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "model", "rules", "off" -> mode;
            default -> {
                log.warn("Okänt rag.gate.mode '{}', använder model", effective.string("rag.gate.mode"));
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
