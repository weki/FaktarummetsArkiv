package se.rightech.fakturummetsarkiv.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

@Service
public class SearchGate {

    private static final Logger log = LoggerFactory.getLogger(SearchGate.class);

    private static final String SYSTEM_PROMPT = """
            You decide whether a user's question requires looking up a company's internal document archive.
            Reply with exactly one word and nothing else:
            - SEARCH if the question is about specific documents, invoices, manuals, internal procedures,
              stored facts, or anything that must be looked up in the archive.
            - DIRECT if the question is general conversation, a greeting, small talk, or a generic question
              that can be answered without the archive.
            """;

    private final OllamaClient client;
    private final OllamaProperties properties;

    public SearchGate(OllamaClient client, OllamaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.gateModel() != null && !properties.gateModel().isBlank();
    }

    public boolean needsSearch(String question) {
        if (!enabled() || question == null || question.isBlank()) {
            return true;
        }
        try {
            return !isDirect(client.chat(properties.gateModel(), SYSTEM_PROMPT, question));
        } catch (RuntimeException ex) {
            log.warn("Kunde inte avgöra om sökning behövs, söker ändå: {}", ex.getMessage());
            return true;
        }
    }

    static boolean isDirect(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        if (lower.contains("direct")) {
            return true;
        }
        if (lower.contains("search")) {
            return false;
        }
        return false;
    }
}
