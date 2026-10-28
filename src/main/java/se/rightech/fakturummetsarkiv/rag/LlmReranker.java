package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rerankar kandidater med en listvis LLM-bedömning via Ollama. */
@Service
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static final String SYSTEM_PROMPT = """
            You rank document passages by how well they answer a question.
            Return ONLY the passage numbers in order of relevance, most relevant first,
            as comma-separated numbers. Include every passage number exactly once.
            Do not explain, quote or add any other text.
            """;

    private final OllamaClient client;
    private final OllamaProperties ollama;
    private final RerankProperties properties;
    private final EffectiveSettings effective;

    @Autowired
    public LlmReranker(OllamaClient client, OllamaProperties ollama, RerankProperties properties,
                       EffectiveSettings effective) {
        this.client = client;
        this.ollama = ollama;
        this.properties = properties;
        this.effective = effective;
    }

    public LlmReranker(OllamaClient client, OllamaProperties ollama, RerankProperties properties) {
        this(client, ollama, properties, EffectiveSettings.baseline(ollama,
                new RagProperties(20, 1200, 200, 8000, "en", 0.3, 4000, "model")));
    }

    @Override
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> candidates, int topK) {
        if (candidates == null || candidates.size() <= 1 || query == null || query.isBlank()) {
            return candidates == null ? List.of() : candidates;
        }
        String model = effective.effectiveModel("rerank");
        if (model == null || model.isBlank()) {
            return candidates;
        }
        try {
            String raw = client.chat(model, SYSTEM_PROMPT, buildPrompt(query, candidates), 0.0,
                    effective.effectiveNumCtx("rerank"), false);
            List<Map<String, Object>> reordered = reorder(raw, candidates);
            if (reordered.isEmpty()) {
                log.warn("Kunde inte tolka rerank-svaret, behåller ursprunglig ordning");
                return candidates;
            }
            return reordered.size() > topK ? reordered.subList(0, topK) : reordered;
        } catch (RuntimeException ex) {
            log.warn("Reranking misslyckades, behåller ursprunglig ordning: {}", ex.getMessage());
            return candidates;
        }
    }

    String buildPrompt(String query, List<Map<String, Object>> candidates) {
        int maxChars = Math.max(80, effective.integer("rag.rerank.max-chars"));
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION:\n").append(query).append("\n\nPASSAGES:\n");
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> candidate = candidates.get(i);
            String text = String.valueOf(candidate.getOrDefault("text", ""));
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "...";
            }
            sb.append('[').append(i + 1).append("] ")
                    .append(candidate.getOrDefault("title", "")).append(": ")
                    .append(text).append('\n');
        }
        return sb.append("\nORDER: ").toString();
    }

    static List<Map<String, Object>> reorder(String raw, List<Map<String, Object>> candidates) {
        if (raw == null || raw.isBlank() || candidates.isEmpty()) {
            return List.of();
        }
        String cleaned = stripThink(raw);
        Set<Integer> order = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(cleaned);
        while (matcher.find()) {
            int number;
            try {
                number = Integer.parseInt(matcher.group());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (number >= 1 && number <= candidates.size()) {
                order.add(number);
            }
        }
        if (order.size() < candidates.size()) {
            for (int i = 1; i <= candidates.size(); i++) {
                order.add(i);
            }
        }
        List<Map<String, Object>> reordered = new ArrayList<>();
        for (Integer number : order) {
            reordered.add(candidates.get(number - 1));
        }
        return reordered;
    }

    private static String stripThink(String text) {
        String cleaned = text.replaceAll("(?is)<think.*?</think\\s*>", " ");
        int open = cleaned.toLowerCase(Locale.ROOT).indexOf("<think");
        return open >= 0 ? cleaned.substring(0, open) : cleaned;
    }
}
