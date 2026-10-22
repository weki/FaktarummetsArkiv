package se.rightech.fakturummetsarkiv.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.chat.WorkspaceSettings;
import se.rightech.fakturummetsarkiv.rag.RetrievalService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval evaluation against a live Ollama and Qdrant instance.
 *
 * <p>The questions in {@code src/test/resources/eval/questions.jsonl} are samples and must be
 * replaced with the owner's own questions and expected documents. Run with {@code ./gradlew eval}.
 */
@Tag("eval")
@SpringBootTest
class EvaluationHarnessTest {

    private static final int TOP_K = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private SettingsStore settingsStore;

    @Test
    void printsRecallAt5AndMrrPerLanguage() throws IOException {
        List<EvalQuestion> questions = loadQuestions();
        assertThat(questions).isNotEmpty();

        WorkspaceSettings settings = settingsStore.get();
        List<String> collections = settings.collections();
        Double threshold = settings.similarityThreshold();
        List<String> documentIds = settings.documentIds();

        Map<String, Metrics> byLanguage = new TreeMap<>();
        Metrics all = new Metrics();

        System.out.println();
        System.out.printf("%-22s %-4s %-6s %-9s %-9s%n", "Question", "Lang", "Rank", "DocHit", "Phrase");
        System.out.println("-".repeat(62));

        for (EvalQuestion question : questions) {
            List<Map<String, Object>> hits = retrievalService.retrieve(
                    question.question(), collections, TOP_K, threshold, documentIds);

            int rank = firstRelevantRank(hits, question);
            double recall = recall(hits, question);
            double phrase = phraseScore(hits, question);

            System.out.printf("%-22s %-4s %-6s %-9.2f %-9.2f%n",
                    question.id(), question.lang(), rank < 0 ? "-" : rank,
                    recall, phrase);

            byLanguage.computeIfAbsent(question.lang(), key -> new Metrics()).add(recall, rank, phrase);
            all.add(recall, rank, phrase);
        }

        System.out.println();
        System.out.printf("%-8s %-9s %-10s %-8s %-8s%n", "Language", "Questions", "Recall@5", "MRR", "Phrase");
        System.out.println("-".repeat(48));
        for (Map.Entry<String, Metrics> entry : byLanguage.entrySet()) {
            printSummary(entry.getKey(), entry.getValue());
        }
        printSummary("all", all);
        System.out.println();
        System.out.println("Note: questions.jsonl contains sample questions; replace them with your own archive.");
    }

    private static void printSummary(String language, Metrics metrics) {
        System.out.printf("%-8s %-9d %-10.2f %-8.2f %-8.2f%n",
                language, metrics.questions, metrics.recall(), metrics.mrr(), metrics.phrase());
    }

    private static int firstRelevantRank(List<Map<String, Object>> hits, EvalQuestion question) {
        for (int i = 0; i < hits.size(); i++) {
            if (isRelevant(hits.get(i), question)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static double recall(List<Map<String, Object>> hits, EvalQuestion question) {
        if (question.expectedDocuments().isEmpty()) {
            return firstRelevantRank(hits, question) > 0 ? 1.0 : 0.0;
        }
        long found = question.expectedDocuments().stream()
                .filter(expected -> hits.stream().anyMatch(hit -> documentMatches(hit, expected)))
                .count();
        return (double) found / question.expectedDocuments().size();
    }

    private static double phraseScore(List<Map<String, Object>> hits, EvalQuestion question) {
        if (question.expectedPhrases().isEmpty()) {
            return 0.0;
        }
        String text = hits.stream()
                .map(hit -> String.valueOf(hit.getOrDefault("text", "")))
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT);
        long found = question.expectedPhrases().stream()
                .filter(phrase -> text.contains(phrase.toLowerCase(Locale.ROOT)))
                .count();
        return (double) found / question.expectedPhrases().size();
    }

    private static boolean isRelevant(Map<String, Object> hit, EvalQuestion question) {
        if (!question.expectedDocuments().isEmpty()) {
            return question.expectedDocuments().stream().anyMatch(expected -> documentMatches(hit, expected));
        }
        String text = String.valueOf(hit.getOrDefault("text", "")).toLowerCase(Locale.ROOT);
        return question.expectedPhrases().stream()
                .anyMatch(phrase -> text.contains(phrase.toLowerCase(Locale.ROOT)));
    }

    private static boolean documentMatches(Map<String, Object> hit, String expected) {
        String needle = expected.toLowerCase(Locale.ROOT);
        String source = String.valueOf(hit.getOrDefault("source", "")).toLowerCase(Locale.ROOT);
        String title = String.valueOf(hit.getOrDefault("title", "")).toLowerCase(Locale.ROOT);
        return source.contains(needle) || title.contains(needle);
    }

    private static List<EvalQuestion> loadQuestions() throws IOException {
        try (InputStream input = EvaluationHarnessTest.class.getResourceAsStream("/eval/questions.jsonl")) {
            if (input == null) {
                throw new IllegalStateException("Hittar inte /eval/questions.jsonl");
            }
            List<EvalQuestion> questions = new ArrayList<>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                questions.add(new EvalQuestion(
                        text(node, "id"),
                        text(node, "lang"),
                        text(node, "question"),
                        strings(node.get("expectedDocuments")),
                        strings(node.get("expectedPhrases"))));
            }
            return questions;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                if (!value.isNull() && !value.asString().isBlank()) {
                    values.add(value.asString());
                }
            }
        }
        return values;
    }

    private record EvalQuestion(String id, String lang, String question,
                                List<String> expectedDocuments, List<String> expectedPhrases) {
    }

    private static final class Metrics {
        private int questions;
        private double recall;
        private double reciprocalRank;
        private double phrase;

        void add(double recall, int rank, double phrase) {
            questions++;
            this.recall += recall;
            this.reciprocalRank += rank > 0 ? 1.0 / rank : 0.0;
            this.phrase += phrase;
        }

        double recall() {
            return questions == 0 ? 0.0 : recall / questions;
        }

        double mrr() {
            return questions == 0 ? 0.0 : reciprocalRank / questions;
        }

        double phrase() {
            return questions == 0 ? 0.0 : phrase / questions;
        }
    }
}
