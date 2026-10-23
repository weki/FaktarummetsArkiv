package se.rightech.fakturummetsarkiv.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.rightech.fakturummetsarkiv.chat.ChatProperties;
import se.rightech.fakturummetsarkiv.chat.ChatService;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.chat.WorkspaceSettings;
import se.rightech.fakturummetsarkiv.grounding.GroundingResult;
import se.rightech.fakturummetsarkiv.grounding.GroundingValidator;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import se.rightech.fakturummetsarkiv.rag.HybridProperties;
import se.rightech.fakturummetsarkiv.rag.QdrantProperties;
import se.rightech.fakturummetsarkiv.rag.RagProperties;
import se.rightech.fakturummetsarkiv.rag.RerankProperties;
import se.rightech.fakturummetsarkiv.rag.RetrievalService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answer and retrieval evaluation against a live Ollama and Qdrant instance.
 *
 * <p>The questions in {@code src/test/resources/eval/questions.jsonl} are samples and must be
 * replaced with the owner's own questions and expected documents. Run with {@code ./gradlew eval}.
 */
@Tag("eval")
@SpringBootTest
class EvaluationHarnessTest {

    private static final int TOP_K = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private GroundingValidator groundingValidator;

    @Autowired
    private SettingsStore settingsStore;

    @Autowired
    private OllamaProperties ollama;

    @Autowired
    private RagProperties rag;

    @Autowired
    private QdrantProperties qdrant;

    @Autowired
    private ChatProperties chat;

    @Autowired
    private HybridProperties hybrid;

    @Autowired
    private RerankProperties rerank;

    @Test
    void printsRetrievalAndAnswerMetricsAndWritesBaseline() throws IOException {
        List<EvalQuestion> questions = loadQuestions();
        assertThat(questions).isNotEmpty();

        WorkspaceSettings settings = settingsStore.get();
        List<String> collections = settings.collections();
        Double threshold = settings.similarityThreshold();
        List<String> documentIds = settings.documentIds();

        Map<String, RetrievalMetrics> retrievalByLanguage = new TreeMap<>();
        RetrievalMetrics retrievalAll = new RetrievalMetrics();
        Map<String, AnswerMetrics> answersByLanguage = new TreeMap<>();
        AnswerMetrics answersAll = new AnswerMetrics();
        List<Map<String, Object>> questionResults = new ArrayList<>();

        System.out.println();
        System.out.printf("%-22s %-4s %-6s %-7s %-7s %-7s %-7s %-7s %-7s%n",
                "Question", "Lang", "Rank", "Recall", "Phrase", "Fact", "CitRec", "CitPrec", "NoAns");
        System.out.println("-".repeat(84));

        for (EvalQuestion question : questions) {
            List<Map<String, Object>> hits = retrievalService.retrieve(
                    question.question(), collections, TOP_K, threshold, documentIds);

            int rank = firstRelevantRank(hits, question);
            double retrievalRecall = recall(hits, question);
            double phrase = phraseScore(hits, question);

            List<Map<String, Object>> answerSources = new ArrayList<>();
            ChatService.StreamResult answer = chatService.streamChat(
                    question.question(), "eval-" + question.id(), collections, documentIds,
                    token -> { }, thought -> { },
                    sources -> {
                        answerSources.clear();
                        answerSources.addAll(sources);
                    },
                    token -> { }, plan -> { });

            double factRecall = EvalMetrics.factRecall(answer.answer(), question.expectedFacts());
            double citationRecall = EvalMetrics.citationRecall(answer.answer(), answerSources,
                    question.expectedCitations());
            double citationPrecision = EvalMetrics.citationPrecision(answer.answer(), answerSources,
                    question.expectedCitations());
            boolean noAnswerCorrect = EvalMetrics.noAnswerCorrect(question.expectNoAnswer(), answer.answer());
            boolean correct = EvalMetrics.answerCorrect(question.expectNoAnswer(), answer.answer(),
                    question.expectedFacts());

            GroundingResult validation = null;
            if (Boolean.TRUE.equals(settings.validateGrounding()) && answer.searched()) {
                validation = groundingValidator.validate(question.question(), answerSources, answer.answer(),
                        settings.validatorModel(), true);
            }
            Double brier = validation == null || validation.supported() == null
                    ? null : EvalMetrics.brier(validation.supported(), correct);

            System.out.printf("%-22s %-4s %-6s %-7.2f %-7.2f %-7.2f %-7.2f %-7.2f %-7s%n",
                    question.id(), question.lang(), rank < 0 ? "-" : rank,
                    retrievalRecall, phrase, factRecall, citationRecall, citationPrecision,
                    noAnswerCorrect ? "yes" : "NO");

            retrievalByLanguage.computeIfAbsent(question.lang(), key -> new RetrievalMetrics())
                    .add(retrievalRecall, rank, phrase);
            retrievalAll.add(retrievalRecall, rank, phrase);
            answersByLanguage.computeIfAbsent(question.lang(), key -> new AnswerMetrics())
                    .add(question, factRecall, citationRecall, citationPrecision, noAnswerCorrect, brier);
            answersAll.add(question, factRecall, citationRecall, citationPrecision, noAnswerCorrect, brier);

            questionResults.add(questionResult(question, answer, answerSources, hits, rank,
                    retrievalRecall, phrase, factRecall, citationRecall, citationPrecision,
                    noAnswerCorrect, correct, validation, brier));
        }

        System.out.println();
        System.out.printf("%-8s %-9s %-10s %-8s %-8s%n", "Language", "Questions", "Recall@5", "MRR", "Phrase");
        System.out.println("-".repeat(48));
        for (Map.Entry<String, RetrievalMetrics> entry : retrievalByLanguage.entrySet()) {
            printRetrievalSummary(entry.getKey(), entry.getValue());
        }
        printRetrievalSummary("all", retrievalAll);

        System.out.println();
        System.out.printf("%-8s %-9s %-8s %-8s %-9s %-9s%n",
                "Language", "Questions", "Fact", "CitRec", "CitPrec", "NoAns");
        System.out.println("-".repeat(56));
        for (Map.Entry<String, AnswerMetrics> entry : answersByLanguage.entrySet()) {
            printAnswerSummary(entry.getKey(), entry.getValue());
        }
        printAnswerSummary("all", answersAll);
        System.out.println();
        System.out.println("Note: questions.jsonl contains sample questions; replace them with your own archive.");

        Path baseline = writeBaseline(settings, questionResults, retrievalAll, answersAll);
        System.out.println("Baseline written to " + baseline);
    }

    private static void printRetrievalSummary(String language, RetrievalMetrics metrics) {
        System.out.printf("%-8s %-9d %-10.2f %-8.2f %-8.2f%n",
                language, metrics.questions, metrics.recall(), metrics.mrr(), metrics.phrase());
    }

    private static void printAnswerSummary(String language, AnswerMetrics metrics) {
        System.out.printf("%-8s %-9d %-8.2f %-8.2f %-9.2f %-9.2f%n",
                language, metrics.questions, metrics.factRecall(), metrics.citationRecall(),
                metrics.citationPrecision(), metrics.noAnswerAccuracy());
    }

    private Map<String, Object> questionResult(EvalQuestion question, ChatService.StreamResult answer,
                                               List<Map<String, Object>> answerSources,
                                               List<Map<String, Object>> hits, int rank,
                                               double retrievalRecall, double phrase,
                                               double factRecall, double citationRecall,
                                               double citationPrecision, boolean noAnswerCorrect,
                                               boolean correct, GroundingResult validation, Double brier) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.id());
        result.put("lang", question.lang());
        result.put("question", question.question());
        result.put("answer", answer.answer());
        result.put("searched", answer.searched());
        result.put("expectNoAnswer", question.expectNoAnswer());
        result.put("noAnswerCorrect", noAnswerCorrect);
        result.put("answerCorrect", correct);
        result.put("retrievalRank", rank);
        result.put("retrievalRecall", retrievalRecall);
        result.put("phraseScore", phrase);
        result.put("factRecall", factRecall);
        result.put("missingFacts", EvalMetrics.missingFacts(answer.answer(), question.expectedFacts()));
        result.put("citationRecall", citationRecall);
        result.put("citationPrecision", citationPrecision);
        result.put("missingCitations", EvalMetrics.missingCitations(answer.answer(), answerSources,
                question.expectedCitations()));
        result.put("citedDocuments", EvalMetrics.citedDocuments(answer.answer(), answerSources));
        result.put("retrievedDocuments", hits.stream()
                .map(hit -> String.valueOf(hit.getOrDefault("title", hit.getOrDefault("source", ""))))
                .toList());
        result.put("brier", brier);
        result.put("notes", question.notes());
        if (validation != null) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("supported", validation.supported());
            check.put("verdict", validation.verdict());
            check.put("reason", validation.reason());
            check.put("unsupportedClaims", validation.unsupportedClaims());
            result.put("validation", check);
        }
        return result;
    }

    private Path writeBaseline(WorkspaceSettings settings, List<Map<String, Object>> results,
                               RetrievalMetrics retrieval, AnswerMetrics answers) throws IOException {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("generatedAt", Instant.now().toString());
        baseline.put("config", config(settings));
        baseline.put("retrieval", Map.of(
                "questions", retrieval.questions,
                "recallAt5", retrieval.recall(),
                "mrr", retrieval.mrr(),
                "phrase", retrieval.phrase()));
        baseline.put("answer", Map.of(
                "questions", answers.questions,
                "factRecall", answers.factRecall(),
                "citationRecall", answers.citationRecall(),
                "citationPrecision", answers.citationPrecision(),
                "noAnswerAccuracy", answers.noAnswerAccuracy(),
                "brier", answers.brier()));
        baseline.put("questions", results);

        Path directory = Path.of("eval");
        Files.createDirectories(directory);
        Path file = directory.resolve("baseline-" + STAMP.format(Instant.now()) + ".json");
        Files.writeString(file, JSON.writeValueAsString(baseline), StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> config(WorkspaceSettings settings) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chatModel", settings.chatModel());
        config.put("validatorModel", settings.validatorModel());
        config.put("refineModel", settings.refineModel());
        config.put("gateModel", settings.gateModel());
        config.put("rewriteModel", settings.rewriteModel());
        config.put("embeddingModel", settings.embeddingModel());
        config.put("answerLanguage", settings.answerLanguage());
        config.put("temperature", settings.temperature());
        config.put("topN", settings.topN());
        config.put("threshold", settings.similarityThreshold());
        config.put("historySize", settings.historySize());
        config.put("maxSourceChars", rag.maxSourceChars());
        config.put("maxHistoryChars", rag.maxHistoryChars());
        config.put("chatNumCtx", ollama.chatNumCtx());
        config.put("validatorNumCtx", ollama.validatorNumCtx());
        config.put("gateNumCtx", ollama.gateNumCtx());
        config.put("rewriteNumCtx", ollama.rewriteNumCtx());
        config.put("chatThinking", chat.thinking());
        config.put("gateMode", rag.gateMode());
        config.put("hybridEnabled", hybrid.enabled());
        config.put("rerankEnabled", rerank.enabled());
        config.put("rerankModel", rerank.model());
        config.put("collection", qdrant.collection());
        config.put("vectorSize", qdrant.vectorSize());
        return config;
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
                        strings(node.get("expectedPhrases")),
                        strings(node.get("expectedFacts")),
                        strings(node.get("expectedCitations")),
                        bool(node, "expectNoAnswer"),
                        text(node, "notes")));
            }
            return questions;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.asBoolean();
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
                                List<String> expectedDocuments, List<String> expectedPhrases,
                                List<String> expectedFacts, List<String> expectedCitations,
                                boolean expectNoAnswer, String notes) {
    }

    private static final class RetrievalMetrics {
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

    private static final class AnswerMetrics {
        private int questions;
        private int factQuestions;
        private int citationQuestions;
        private int noAnswerQuestions;
        private int brierQuestions;
        private double factRecall;
        private double citationRecall;
        private double citationPrecision;
        private double noAnswerCorrect;
        private double brier;

        void add(EvalQuestion question, double factRecall, double citationRecall,
                 double citationPrecision, boolean noAnswerCorrect, Double brier) {
            questions++;
            if (!question.expectedFacts().isEmpty()) {
                factQuestions++;
                this.factRecall += factRecall;
            }
            if (!question.expectedCitations().isEmpty()) {
                citationQuestions++;
                this.citationRecall += citationRecall;
                this.citationPrecision += citationPrecision;
            }
            noAnswerQuestions++;
            this.noAnswerCorrect += noAnswerCorrect ? 1.0 : 0.0;
            if (brier != null && !brier.isNaN()) {
                brierQuestions++;
                this.brier += brier;
            }
        }

        double factRecall() {
            return factQuestions == 0 ? 0.0 : factRecall / factQuestions;
        }

        double citationRecall() {
            return citationQuestions == 0 ? 0.0 : citationRecall / citationQuestions;
        }

        double citationPrecision() {
            return citationQuestions == 0 ? 0.0 : citationPrecision / citationQuestions;
        }

        double noAnswerAccuracy() {
            return noAnswerQuestions == 0 ? 0.0 : noAnswerCorrect / noAnswerQuestions;
        }

        double brier() {
            return brierQuestions == 0 ? Double.NaN : brier / brierQuestions;
        }
    }
}
