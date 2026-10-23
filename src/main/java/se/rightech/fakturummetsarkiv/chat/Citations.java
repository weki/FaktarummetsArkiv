package se.rightech.fakturummetsarkiv.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tar bort numeriska källhänvisningar från historik, men lämnar kodblock orörda. */
public final class Citations {

    private static final Pattern CODE = Pattern.compile("```.*?```|`[^`\\n]*`", Pattern.DOTALL);
    private static final Pattern MARKER = Pattern.compile(
            "[ \\t]*\\[\\s*\\d+(?:\\s*[,;]\\s*\\d+|\\s*[-–]\\s*\\d+)*\\s*\\]");

    private Citations() {
    }

    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = CODE.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(stripPlain(text.substring(last, matcher.start())));
            out.append(matcher.group());
            last = matcher.end();
        }
        out.append(stripPlain(text.substring(last)));
        return out.toString();
    }

    static List<Map<String, String>> stripAssistantHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> cleaned = new ArrayList<>(history.size());
        for (Map<String, String> message : history) {
            String content = message.get("content");
            if ("assistant".equals(message.get("role")) && content != null) {
                cleaned.add(Map.of("role", "assistant", "content", strip(content)));
            } else {
                cleaned.add(message);
            }
        }
        return List.copyOf(cleaned);
    }

    private static String stripPlain(String text) {
        String withoutMarkers = MARKER.matcher(text).replaceAll("");
        return withoutMarkers.replaceAll("[ \\t]{2,}", " ");
    }
}
