package se.rightech.fakturummetsarkiv.grounding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourceText {

    /** Färdig källblockstext plus de källor som faktiskt fick plats. */
    public record Block(String text, List<Map<String, Object>> sources) {
    }

    private SourceText() {
    }

    /**
     * Numrerar och saniterar källor. Hela källblock tas bort när budgeten inte räcker, så att
     * modellen, valideraren och refineraren alltid ser exakt samma text.
     */
    public static Block numberedBlock(List<Map<String, Object>> sources, int maxChars) {
        List<Map<String, Object>> included = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            return new Block("", List.of());
        }
        StringBuilder sb = new StringBuilder();
        int budget = Math.max(0, maxChars);
        int number = 1;
        for (Map<String, Object> source : sources) {
            String title = sanitize(source.get("title"));
            String text = sanitize(source.get("text"));
            Object page = source.get("page");
            String header = "<<<SOURCE " + number + ": " + title
                    + (page == null ? "" : ", page " + page) + ">>>";
            String block = header + "\n" + text + "\n<<<END SOURCE>>>\n\n";
            if (block.length() > budget) {
                break;
            }
            sb.append(block);
            budget -= block.length();
            Map<String, Object> copy = new LinkedHashMap<>(source);
            copy.put("citationNumber", number);
            included.add(copy);
            number++;
        }
        return new Block(sb.toString(), List.copyOf(included));
    }

    /** Tar bort sekvenser som kan imitera källblockens avgränsare. */
    public static String sanitize(Object value) {
        return str(value).replace("<<<", "< < <").replace(">>>", "> > >");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
