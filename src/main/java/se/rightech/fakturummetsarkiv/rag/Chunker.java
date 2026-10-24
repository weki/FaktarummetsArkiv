package se.rightech.fakturummetsarkiv.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {

    private final RagProperties properties;
    private final EffectiveSettings effective;

    @Autowired
    public Chunker(RagProperties properties, EffectiveSettings effective) {
        this.properties = properties;
        this.effective = effective;
    }

    public Chunker(RagProperties properties) {
        this(properties, EffectiveSettings.baseline(nullSafeOllama(), properties));
    }

    private static se.rightech.fakturummetsarkiv.llm.OllamaProperties nullSafeOllama() {
        return new se.rightech.fakturummetsarkiv.llm.OllamaProperties("http://localhost:11434", "model",
                "embedding", java.time.Duration.ofSeconds(30), "validator", true, "", "", "",
                8192, 4096, 2048, 2048, false, false, false, false, false, null);
    }

    public record Chunk(int index, String text, int start) {
    }

    public List<Chunk> chunk(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, effective.integer("rag.chunk-size"));
        int overlap = Math.max(0, Math.min(effective.integer("rag.chunk-overlap"), size - 1));
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                int boundary = lastBoundary(normalized, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String chunkText = normalized.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new Chunk(index++, chunkText, start));
            }
            if (end >= normalized.length()) {
                break;
            }
            int next = end - overlap;
            start = next <= start ? end : next;
        }
        return chunks;
    }

    private static int lastBoundary(String text, int start, int end) {
        int min = start + Math.max(1, (end - start) / 2);
        for (int i = end - 1; i >= min; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (c == '.' || c == '!' || c == '?' || c == ';') {
                return i + 1;
            }
        }
        return end;
    }
}
