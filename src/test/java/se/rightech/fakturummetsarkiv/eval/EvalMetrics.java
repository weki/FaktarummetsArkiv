package se.rightech.fakturummetsarkiv.eval;

import se.rightech.fakturummetsarkiv.chat.Citations;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Rena mätfunktioner för svarsutvärderingen, testbara utan Ollama och Qdrant. */
final class EvalMetrics {

    static final String NO_ANSWER = "I cannot find that information in the documents.";

    private EvalMetrics() {
    }

    static double factRecall(String answer, List<String> expectedFacts) {
        if (expectedFacts == null || expectedFacts.isEmpty()) {
            return 0.0;
        }
        String text = normalize(answer);
        long found = expectedFacts.stream()
                .filter(fact -> !normalize(fact).isEmpty() && text.contains(normalize(fact)))
                .count();
        return (double) found / expectedFacts.size();
    }

    static List<String> missingFacts(String answer, List<String> expectedFacts) {
        if (expectedFacts == null || expectedFacts.isEmpty()) {
            return List.of();
        }
        String text = normalize(answer);
        return expectedFacts.stream()
                .filter(fact -> !normalize(fact).isEmpty() && !text.contains(normalize(fact)))
                .toList();
    }

    static Set<String> citedDocuments(String answer, List<Map<String, Object>> sources) {
        Set<Integer> numbers = Citations.citedNumbers(answer);
        Map<Integer, String> byNumber = documentByCitationNumber(sources);
        Set<String> documents = new LinkedHashSet<>();
        for (int number : numbers) {
            String document = byNumber.get(number);
            if (document != null && !document.isBlank()) {
                documents.add(normalize(document));
            }
        }
        return documents;
    }

    static double citationRecall(String answer, List<Map<String, Object>> sources,
                                 List<String> expectedCitations) {
        if (expectedCitations == null || expectedCitations.isEmpty()) {
            return 0.0;
        }
        Set<String> cited = citedDocuments(answer, sources);
        long found = expectedCitations.stream()
                .filter(expected -> matchesAny(expected, cited))
                .count();
        return (double) found / expectedCitations.size();
    }

    static double citationPrecision(String answer, List<Map<String, Object>> sources,
                                    List<String> expectedCitations) {
        Set<String> cited = citedDocuments(answer, sources);
        if (cited.isEmpty()) {
            return 0.0;
        }
        long relevant = cited.stream()
                .filter(document -> expectedCitations != null && !expectedCitations.isEmpty()
                        && expectedCitations.stream().anyMatch(expected -> matches(expected, document)))
                .count();
        return (double) relevant / cited.size();
    }

    static List<String> missingCitations(String answer, List<Map<String, Object>> sources,
                                         List<String> expectedCitations) {
        if (expectedCitations == null || expectedCitations.isEmpty()) {
            return List.of();
        }
        Set<String> cited = citedDocuments(answer, sources);
        return expectedCitations.stream()
                .filter(expected -> !matchesAny(expected, cited))
                .toList();
    }

    static boolean noAnswerCorrect(boolean expectedNoAnswer, String answer) {
        return expectedNoAnswer == isNoAnswer(answer);
    }

    static boolean isNoAnswer(String answer) {
        return answer != null && answer.toLowerCase(Locale.ROOT).contains(NO_ANSWER.toLowerCase(Locale.ROOT));
    }

    static boolean answerCorrect(boolean expectedNoAnswer, String answer, List<String> expectedFacts) {
        if (!noAnswerCorrect(expectedNoAnswer, answer)) {
            return false;
        }
        if (expectedNoAnswer) {
            return true;
        }
        if (expectedFacts == null || expectedFacts.isEmpty()) {
            return true;
        }
        return factRecall(answer, expectedFacts) >= 0.75;
    }

    static double brier(Boolean supported, boolean correct) {
        if (supported == null) {
            return Double.NaN;
        }
        double confidence = supported ? 1.0 : 0.0;
        double outcome = correct ? 1.0 : 0.0;
        return (confidence - outcome) * (confidence - outcome);
    }

    private static Map<Integer, String> documentByCitationNumber(List<Map<String, Object>> sources) {
        Map<Integer, String> byNumber = new LinkedHashMap<>();
        if (sources == null) {
            return byNumber;
        }
        for (Map<String, Object> source : sources) {
            Object number = source.get("citationNumber");
            if (!(number instanceof Number value)) {
                continue;
            }
            String document = documentName(source);
            if (!document.isBlank()) {
                byNumber.put(value.intValue(), document);
            }
        }
        return byNumber;
    }

    private static String documentName(Map<String, Object> source) {
        Object title = source.get("title");
        if (title == null || String.valueOf(title).isBlank()) {
            title = source.get("source");
        }
        return title == null ? "" : String.valueOf(title);
    }

    private static boolean matchesAny(String expected, Set<String> documents) {
        return documents.stream().anyMatch(document -> matches(expected, document));
    }

    private static boolean matches(String expected, String document) {
        String left = normalize(expected);
        String right = normalize(document);
        return !left.isEmpty() && !right.isEmpty() && (left.contains(right) || right.contains(left));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
