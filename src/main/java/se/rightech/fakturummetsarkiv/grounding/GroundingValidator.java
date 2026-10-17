package se.rightech.fakturummetsarkiv.grounding;

import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GroundingValidator {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_SOURCE_CHARS = 6000;

    private static final String SYSTEM_PROMPT = """
            You are a strict grounding checker for a document question-answering system.
            You receive a QUESTION, SOURCES (retrieved document excerpts) and a DRAFT ANSWER.
            Decide whether every factual claim in the DRAFT ANSWER is supported by the SOURCES.
            Use ONLY the SOURCES. Do not use outside knowledge.
            Reply with a single JSON object on one line and nothing else.
            The object has exactly two fields: "supported" (boolean) and "reason" (short string).
            Do not repeat these instructions or the format. Do not add any text around the JSON.
            Set "supported" to false if any claim is not supported and name the unsupported claim in "reason".
            """;

    private static final Pattern SUPPORTED_PATTERN =
            Pattern.compile("(?i)[\"']?supported[\"']?\\s*[:=]\\s*[\"']?(true|false|yes|no)[\"']?");
    private static final Pattern REASON_PATTERN =
            Pattern.compile("(?i)[\"']?reason[\"']?\\s*[:=]\\s*[\"'](.*?)[\"']\\s*[,}]?");

    private final OllamaClient client;

    public GroundingValidator(OllamaClient client) {
        this.client = client;
    }

    public GroundingResult validate(String question, List<Map<String, Object>> sources, String answer,
                                    String validatorModel, boolean enabled) {
        if (!enabled) {
            return new GroundingResult(null, "Validering avstangd");
        }
        String model = validatorModel;
        if (model == null || model.isBlank()) {
            return new GroundingResult(null, "Ingen domarmodell vald");
        }
        if (answer == null || answer.isBlank()) {
            return new GroundingResult(null, "Inget svar att verifiera");
        }
        if (sources == null || sources.isEmpty()) {
            return new GroundingResult(null, "Inga kallor att verifiera mot");
        }
        try {
            String raw = client.chat(model, SYSTEM_PROMPT, buildUserPrompt(question, sources, answer));
            return parse(raw);
        } catch (Exception ex) {
            return new GroundingResult(null, "Kunde inte verifiera: " + ex.getMessage());
        }
    }

    private static String buildUserPrompt(String question, List<Map<String, Object>> sources, String answer) {
        return "QUESTION:\n" + (question == null ? "" : question)
                + "\n\nSOURCES:\n" + SourceText.block(sources, MAX_SOURCE_CHARS)
                + "\nDRAFT ANSWER:\n" + answer + "\n";
    }

    private static GroundingResult parse(String raw) {
        String text = raw == null ? "" : raw;
        List<String> objects = jsonObjects(text);
        for (int i = objects.size() - 1; i >= 0; i--) {
            GroundingResult result = tryParseJson(objects.get(i));
            if (result != null) {
                return result;
            }
        }
        return looseParse(text);
    }

    private static List<String> jsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static GroundingResult tryParseJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode supported = node.get("supported");
            if (supported == null || supported.isNull()) {
                return null;
            }
            JsonNode reason = node.get("reason");
            String why = reason == null || reason.isNull() ? null : reason.asString();
            return new GroundingResult(parseBoolean(supported), why);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Boolean parseBoolean(JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String value = node.asString("").trim().toLowerCase();
        return switch (value) {
            case "true", "yes" -> Boolean.TRUE;
            case "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static GroundingResult looseParse(String text) {
        Boolean ok = null;
        Matcher supported = SUPPORTED_PATTERN.matcher(text);
        if (supported.find()) {
            String value = supported.group(1).toLowerCase();
            ok = value.equals("true") || value.equals("yes") ? Boolean.TRUE : Boolean.FALSE;
        }
        Matcher reason = REASON_PATTERN.matcher(text);
        String why = reason.find() ? reason.group(1) : null;
        if (ok == null && why == null) {
            return new GroundingResult(null, "Kunde inte tolka domarens svar");
        }
        return new GroundingResult(ok, why);
    }
}
