package se.rightech.fakturummetsarkiv.grounding;

import java.util.List;
import java.util.Map;

final class SourceText {

    private SourceText() {
    }

    static String block(List<Map<String, Object>> sources, int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (sources == null) {
            return "";
        }
        int budget = maxChars;
        for (Map<String, Object> source : sources) {
            if (budget <= 0) {
                break;
            }
            String title = str(source.get("title"));
            String text = str(source.get("text"));
            String block = "--- " + title + " ---\n" + text + "\n\n";
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

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
