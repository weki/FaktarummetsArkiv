package se.rightech.fakturummetsarkiv.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProcedureExtractor {
    private ProcedureExtractor() {
    }

    // Opening fence must occupy its own line. Language tag is optional.
    private static final Pattern OPEN = Pattern.compile("^```(yaml|yml)?\\s*$");
    private static final Pattern CLOSE = Pattern.compile("^```\\s*$");

    // Uses Jackson 2 (com.fasterxml) explicitly; see build.gradle dependency jackson-dataformat-yaml.
    // Main code uses Jackson 3 (tools.jackson); this extractor keeps Jackson 2 for YAML parsing parity with DocuForgeExporter.
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Returns the last qualifying fenced block's content, or null if none. */
    public static String extract(String answer) {
        if (answer == null) return null;
        String[] lines = answer.split("\\n", -1);
        List<String> candidates = new ArrayList<>();
        int i = 0;
        int n = lines.length;
        while (i < n) {
            // require opening fence to start the (trimmed) line exactly
            if (!isOpenFence(lines[i])) {
                i++;
                continue;
            }
            String lang = fenceLang(lines[i]);
            // collect until closing fence on its own line
            int j = i + 1;
            List<String> body = new ArrayList<>();
            boolean closed = false;
            while (j < n) {
                if (isCloseFence(lines[j])) {
                    closed = true;
                    break;
                }
                body.add(lines[j]);
                j++;
            }
            if (!closed) {
                // unbalanced: stop
                break;
            }
            String content = String.join("\n", body).trim();
            if (qualifies(lang, content)) {
                candidates.add(content);
            }
            i = j + 1;
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(candidates.size() - 1);
    }

    private static boolean isOpenFence(String line) {
        return OPEN.matcher(line.trim()).matches();
    }

    private static String fenceLang(String line) {
        Matcher m = OPEN.matcher(line.trim());
        if (!m.matches()) return null;
        String g = m.group(1);
        return g == null ? "" : g.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isCloseFence(String line) {
        return CLOSE.matcher(line.trim()).matches();
    }

    private static boolean qualifies(String lang, String content) {
        if (content == null || content.isBlank()) return false;
        if ("yaml".equals(lang) || "yml".equals(lang)) return true;
        if (lang != null && !lang.isEmpty()) return false; // other languages never
        // unmarked: must parse as YAML map with both metadata and sections
        try {
            Object o = YAML.readValue(content, Object.class);
            if (!(o instanceof Map)) return false;
            Map<?, ?> m = (Map<?, ?>) o;
            return m.containsKey("metadata") && m.containsKey("sections");
        } catch (Exception e) {
            return false;
        }
    }
}