package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceOmitYamlTest {

    @Test
    void onlyYamlReplaced() {
        String content = "prosa\n```yaml\nmetadata:\n  title: T\n```\nmore\n```bash\necho hi\n```\nend";
        List<Map<String, String>> history = List.of(Map.of("role", "assistant", "content", content));
        List<Map<String, String>> out = ChatService.omitYamlInHistory(history);
        String c = out.get(0).get("content");
        assertTrue(c.contains("[YAML procedure omitted, see CURRENT PROCEDURE]"));
        assertTrue(c.contains("```bash"));
        assertTrue(c.contains("prosa"));
        assertTrue(c.contains("more"));
    }

    @Test
    void twoBashUnchanged() {
        String content = "```bash\necho hi\n```\ntext\n```bash\necho ho\n```";
        List<Map<String, String>> history = List.of(Map.of("role", "assistant", "content", content));
        List<Map<String, String>> out = ChatService.omitYamlInHistory(history);
        assertEquals(content, out.get(0).get("content"));
    }

    @Test
    void unbalancedUnchanged() {
        String content = "```yaml\nmetadata: x\n";
        List<Map<String, String>> history = List.of(Map.of("role", "assistant", "content", content));
        List<Map<String, String>> out = ChatService.omitYamlInHistory(history);
        assertEquals(content, out.get(0).get("content"));
    }

    @Test
    void noCodeUnchanged() {
        String content = "just text";
        List<Map<String, String>> history = List.of(Map.of("role", "assistant", "content", content));
        List<Map<String, String>> out = ChatService.omitYamlInHistory(history);
        assertEquals(content, out.get(0).get("content"));
    }
}
