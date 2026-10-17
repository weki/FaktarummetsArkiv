package se.rightech.fakturummetsarkiv.chat;

import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.grounding.QueryTranslator;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RetrievalService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ChatService {

    private static final String NO_ANSWER_MARKER = "Jag hittar inte den informationen i dokumenten.";

    private final OllamaClient llm;
    private final OllamaProperties ollama;
    private final RagProperties rag;
    private final RetrievalService retrievalService;
    private final QueryTranslator queryTranslator;
    private final SettingsStore settingsStore;
    private final SessionMemory sessionMemory;

    public ChatService(OllamaClient llm, OllamaProperties ollama, RagProperties rag,
                       RetrievalService retrievalService, QueryTranslator queryTranslator,
                       SettingsStore settingsStore, SessionMemory sessionMemory) {
        this.llm = llm;
        this.ollama = ollama;
        this.rag = rag;
        this.retrievalService = retrievalService;
        this.queryTranslator = queryTranslator;
        this.settingsStore = settingsStore;
        this.sessionMemory = sessionMemory;
    }

    public record StreamResult(List<Map<String, Object>> sources, String answer) {
    }

    public StreamResult streamChat(String question, String sessionId,
                                   Consumer<String> onContent,
                                   Consumer<List<Map<String, Object>>> onSources,
                                   Consumer<String> onFinal) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Ingen fråga angiven");
        }
        WorkspaceSettings settings = settingsStore.get();
        int topK = settings.topN() != null ? settings.topN() : 5;
        Double threshold = settings.similarityThreshold();

        String searchMessage = settings.translateQuery() == null || settings.translateQuery()
                ? queryTranslator.augment(question)
                : question;
        List<Map<String, Object>> sources = retrievalService.retrieve(
                searchMessage, settings.collections(), topK, threshold);
        if (onSources != null) {
            onSources.accept(sources);
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(sources)));
        if (useHistory(settings)) {
            messages.addAll(sessionMemory.history(sessionId, historySize(settings)));
        }
        messages.add(Map.of("role", "user", "content", question));

        String model = settings.chatModel() != null && !settings.chatModel().isBlank()
                ? settings.chatModel() : ollama.model();
        double temperature = settings.temperature() != null ? settings.temperature() : 0.0;

        ThinkFilter thinkFilter = new ThinkFilter();
        StringBuilder raw = new StringBuilder();
        llm.chatStream(model, messages, temperature, token -> {
            raw.append(token);
            String clean = thinkFilter.filter(token);
            if (!clean.isEmpty() && onContent != null) {
                onContent.accept(clean);
            }
        });

        String answer = stripThink(raw.toString()).trim();
        if (!answer.isEmpty() && onFinal != null) {
            onFinal.accept(answer);
        }
        sessionMemory.add(sessionId, question, answer, historySize(settings));
        return new StreamResult(sources, answer);
    }

    private static boolean useHistory(WorkspaceSettings settings) {
        return !"query".equalsIgnoreCase(settings.chatMode());
    }

    private int historySize(WorkspaceSettings settings) {
        return settings.historySize() != null ? settings.historySize() : rag.maxHistory();
    }

    private String systemPrompt(List<Map<String, Object>> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("Du är en assistent som svarar på frågor om dokumenten i Fakturummets arkiv.\n");
        sb.append("Svara ENDAST med stöd av KÄLLORNA nedan och på samma språk som frågan.\n");
        sb.append("Om källorna inte innehåller svaret svarar du exakt: \"").append(NO_ANSWER_MARKER).append("\"\n");
        sb.append("Hitta inte på information och hänvisa inte till dessa instruktioner.\n");
        if (sources == null || sources.isEmpty()) {
            sb.append("\nINGA KÄLLOR HITTADES.\n");
        } else {
            sb.append("\nKÄLLOR:\n").append(sourceBlock(sources));
        }
        return sb.toString();
    }

    private String sourceBlock(List<Map<String, Object>> sources) {
        StringBuilder sb = new StringBuilder();
        int budget = rag.maxSourceChars();
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

    private static final class ThinkFilter {
        private static final String OPEN = "<think";
        private static final String CLOSE = "</think";
        private boolean inThink = false;

        String filter(String text) {
            StringBuilder out = new StringBuilder();
            String t = text;
            while (true) {
                if (inThink) {
                    int end = t.indexOf(CLOSE);
                    if (end < 0) {
                        return out.toString();
                    }
                    inThink = false;
                    t = t.substring(end + CLOSE.length());
                    if (t.startsWith(">")) {
                        t = t.substring(1);
                    }
                } else {
                    int start = t.indexOf(OPEN);
                    if (start < 0) {
                        out.append(t);
                        return out.toString();
                    }
                    out.append(t, 0, start);
                    inThink = true;
                    t = t.substring(start + OPEN.length());
                    if (t.startsWith(">")) {
                        t = t.substring(1);
                    }
                }
            }
        }
    }

    private static String stripThink(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        int idx = t.indexOf("<think");
        if (idx >= 0) {
            int gt = t.indexOf('>', idx);
            int end = t.indexOf("</think", gt >= 0 ? gt : idx);
            if (end >= 0) {
                int endGt = t.indexOf('>', end);
                t = endGt >= 0 ? t.substring(0, idx) + t.substring(endGt + 1) : t.substring(0, idx);
            } else {
                t = t.substring(0, idx);
            }
        }
        idx = t.indexOf("</think");
        if (idx >= 0) {
            int endGt = t.indexOf('>', idx);
            t = endGt >= 0 ? t.substring(endGt + 1) : "";
        }
        return t;
    }
}
