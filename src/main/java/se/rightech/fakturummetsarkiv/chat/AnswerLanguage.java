package se.rightech.fakturummetsarkiv.chat;

import java.util.Locale;

/** Delad språkinstruktion för svar och uppföljningsfrågor. */
public final class AnswerLanguage {

    private AnswerLanguage() {
    }

    public static String answerInstruction(String language) {
        return switch (normalize(language)) {
            case "sv" -> "Always answer in Swedish, regardless of the language of the question.\n";
            case "auto" -> "Answer in the same language as the user's question.\n";
            default -> "Always answer in English, regardless of the language of the question.\n";
        };
    }

    public static String followUpInstruction(String language) {
        return switch (normalize(language)) {
            case "sv" -> "Write every question in Swedish, regardless of the language of the USER QUESTION.\n";
            case "auto" -> "Write every question in the same language as the USER QUESTION.\n";
            default -> "Write every question in English, regardless of the language of the USER QUESTION.\n";
        };
    }

    public static boolean valid(String language) {
        String normalized = normalize(language);
        return normalized.equals("en") || normalized.equals("sv") || normalized.equals("auto");
    }

    private static String normalize(String language) {
        return language == null || language.isBlank() ? "en" : language.trim().toLowerCase(Locale.ROOT);
    }
}
