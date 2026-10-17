package se.rightech.fakturummetsarkiv.grounding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.util.Set;

@Service
public class QueryTranslator {

    private static final Logger log = LoggerFactory.getLogger(QueryTranslator.class);

    private static final String SYSTEM_PROMPT = """
            Du översätter sökfrågor till engelska för att förbättra dokumentsökning i en engelsk dokumentbas.
            Behåll tekniska termer, produktnamn och förkortningar oförändrade (t.ex. Experion, Control Builder).
            Skriv förkortningar med korrekt mellanslag: skriv "OPC UA" (aldrig "OPCUA" eller "OPC-UA").
            Om frågan redan är på engelska lämnar du den oförändrad.
            Svara ENDAST med den engelska översättningen, utan förklaringar, citattecken eller punktlista.
            """;

    private static final Set<String> SWEDISH_MARKERS = Set.of(
            "hur", "vad", "var", "varför", "vilka", "vilken", "vilket", "jag", "du", "man",
            "kan", "ska", "skall", "är", "inte", "och", "eller", "för", "med", "till", "från",
            "på", "av", "den", "det", "som", "om", "när", "blir", "gör", "använder", "hittar",
            "enablar", "aktiverar", "konfigurerar", "installerar", "fungerar", "behöver");

    private final OllamaClient client;
    private final OllamaProperties properties;

    public QueryTranslator(OllamaClient client, OllamaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.translateModel() != null && !properties.translateModel().isBlank();
    }

    public String augment(String question) {
        if (!enabled() || question == null || question.isBlank() || !needsTranslation(question)) {
            return question;
        }
        try {
            String translation = clean(client.chat(properties.translateModel(), SYSTEM_PROMPT, question));
            if (translation.isEmpty() || translation.equalsIgnoreCase(question.trim())) {
                return question;
            }
            return question + "\n" + translation;
        } catch (RuntimeException ex) {
            log.warn("Kunde inte översätta frågan, använder originalet: {}", ex.getMessage());
            return question;
        }
    }

    static boolean needsTranslation(String question) {
        String lower = question.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) > 127) {
                return true;
            }
        }
        for (String token : lower.split("[^a-zåäö]+")) {
            if (!token.isEmpty() && SWEDISH_MARKERS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        int open = t.indexOf("<think");
        if (open >= 0) {
            int gt = t.indexOf('>', open);
            int end = t.indexOf("</think", gt >= 0 ? gt : open);
            t = end >= 0 ? (t.substring(0, open) + t.substring(t.indexOf('>', end) + 1)) : t.substring(0, open);
        }
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("(?i)opc[\\s-]?ua", "OPC UA");
        return t;
    }
}
