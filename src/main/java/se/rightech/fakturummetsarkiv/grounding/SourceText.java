package se.rightech.fakturummetsarkiv.grounding;

import java.util.List;
import java.util.Map;

public final class SourceText {

    private SourceText() {
    }

    public static String block(List<Map<String, Object>> sources, int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (sources == null) {
            return "";
        }
        int budget = maxChars;
        for (Map<String, Object> source : sources) {
            if (budget <= 0) {
                break;
            }
            String title = sanitize(source.get("title"));
            String text = sanitize(source.get("text"));
            String block = "<<<SOURCE: " + title + ">>>\n" + text + "\n<<<END SOURCE>>>\n\n";
            if (block.length() > budget) {
                sb.append(block, 0, budget);
                budget = 0;
            } else {
                sb.append(block);
                budget -= block.length();
            }
        }
        return sb.toString();
    }

    /** Tar bort sekvenser som kan imitera källblockens avgränsare. */
    public static String sanitize(Object value) {
        return str(value).replace("<<<", "< < <").replace(">>>", "> > >");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
