package se.rightech.fakturummetsarkiv.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProcedureExtractor {
    private ProcedureExtractor() {
    }

    private static final Pattern FENCED = Pattern.compile("```(?:yaml|yml)?\\s*\\n(.*?)```", Pattern.DOTALL);

    /** Returns the last fenced yaml block's content, or null if none. */
    public static String extract(String answer) {
        if (answer == null) return null;
        Matcher m = FENCED.matcher(answer);
        String last = null;
        while (m.find()) {
            String content = m.group(1);
            if (content != null && content.contains("metadata") && content.contains("sections")) {
                last = content.trim();
            } else if (last == null && content != null && !content.isBlank()) {
                // fallback: last fenced block
                last = content.trim();
            }
        }
        if (last == null) {
            // try any fenced block
            Matcher m2 = FENCED.matcher(answer);
            while (m2.find()) last = m2.group(1).trim();
        }
        return last;
    }
}
