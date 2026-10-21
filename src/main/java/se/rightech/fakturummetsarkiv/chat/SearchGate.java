package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

@Service
public class SearchGate {

    private static final Logger log = LoggerFactory.getLogger(SearchGate.class);

    public record Decision(boolean search, String query) {
    }

    private static final String SYSTEM_PROMPT = """
            You are a gatekeeper for a company's internal document archive (invoices, manuals, technical docs).

            Decide if the user's message needs a document search.
            - If NO search is needed (greeting, thanks, small talk, clearly general knowledge), reply exactly: DIRECT
            - If search IS needed, reply with a concise English search query (3-12 keywords) optimized for
              semantic retrieval in the archive. No quotes, no explanation, one line only.

            Examples:
            Message: hej                        -> DIRECT
            Message: tack                       -> DIRECT
            Message: vad är OPC UA?              -> OPC UA definition overview
            Message: hur mår du?                -> DIRECT
            Message: what does invoice 42 say?   -> invoice 42 content details
            Message: hur aktiverar jag OPC UA?   -> enable OPC UA configuration steps

            Reply with either DIRECT or an English search query. Nothing else.
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;

    public SearchGate(OllamaClient client, OllamaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean enabled() {
        return enabled(properties.gateModel());
    }

    public boolean enabled(String gateModel) {
        return gateModel != null && !gateModel.isBlank();
    }

    public Decision decide(String question) {
        return decide(question, properties.gateModel());
    }

    public Decision decide(String question, String gateModel) {
        if (!enabled(gateModel) || question == null || question.isBlank()) {
            return new Decision(true, question);
        }
        try {
            String raw = client.chat(gateModel, SYSTEM_PROMPT, question).trim();
            if (raw.isEmpty()) {
                log.warn("Sökgaten gav tomt svar, söker ändå");
                return new Decision(true, question);
            }
            String first = raw.split("\\s+", 2)[0].replaceAll("[^A-Za-z]", "").toUpperCase();
            if ("DIRECT".equals(first)) {
                return new Decision(false, question);
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
}
