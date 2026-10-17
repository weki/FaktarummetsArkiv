package se.rightech.fakturummetsarkiv.chat;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionMemory {

    private final Map<String, Deque<Map<String, String>>> sessions = new ConcurrentHashMap<>();

    public List<Map<String, String>> history(String sessionId, int maxHistory) {
        if (sessionId == null || sessionId.isBlank() || maxHistory <= 0) {
            return List.of();
        }
        Deque<Map<String, String>> turns = sessions.get(sessionId);
        if (turns == null) {
            return List.of();
        }
        synchronized (turns) {
            return new ArrayList<>(turns);
        }
    }

    public void add(String sessionId, String userMessage, String assistantMessage, int maxHistory) {
        if (sessionId == null || sessionId.isBlank() || maxHistory <= 0) {
            return;
        }
        Deque<Map<String, String>> turns = sessions.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (turns) {
            if (userMessage != null && !userMessage.isBlank()) {
                turns.addLast(Map.of("role", "user", "content", userMessage));
            }
            if (assistantMessage != null && !assistantMessage.isBlank()) {
                turns.addLast(Map.of("role", "assistant", "content", assistantMessage));
            }
            while (turns.size() > maxHistory) {
                turns.removeFirst();
            }
        }
    }

    public void clear(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.remove(sessionId);
        }
    }
}
