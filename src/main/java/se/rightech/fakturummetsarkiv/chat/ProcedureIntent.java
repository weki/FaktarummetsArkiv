package se.rightech.fakturummetsarkiv.chat;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies the user's intent regarding a procedure, independent of the search gate.
 * The gate decides WHERE the answer is fetched; intent decides WHAT may be done to the procedure.
 */
public class ProcedureIntent {

    public enum Intent { NONE, CREATE, EDIT, DISCUSS, SHOW }

    // Imperative creation words (whole words, Swedish input)
    private static final Set<String> CREATE_WORDS = Set.of(
            "skapa", "skriv", "gör", "generera", "bygg", "ta fram", "utforma", "formulera",
            "create", "write", "make", "generate", "build", "draft", "compose");
    private static final Set<String> PROCEDURE_WORDS = Set.of(
            "procedur", "procedure", "sop", "yaml", "steg-för-steg", "step-by-step", "step by step", "manual");

    private static final Set<String> EDIT_WORDS = Set.of(
            "lägg", "ta bort", "slå ihop", "dela", "flytta", "ändra", "skriv om", "gör kritiskt",
            "byt", "uppdatera", "modifiera", "justera", "lägg till", "radera",
            "add", "remove", "merge", "split", "move", "change", "rewrite", "make critical", "update", "modify", "adjust");

    private static final Set<String> SHOW_WORDS = Set.of("visa", "skriv ut", "show", "print", "display", "visa yaml");

    private static final Set<String> CONFIRM_WORDS = Set.of("ja", "kör", "gör det", "lägg in det", "ok", "okej", "yes", "do it", "apply");

    public static Intent classify(String question, boolean hasProcedure, boolean hasAttachment, String lastAssistantMessage) {
        if (question == null || question.isBlank()) return Intent.NONE;
        String q = question.toLowerCase(Locale.ROOT).trim();
        boolean mentionsProc = containsAnyWord(q, PROCEDURE_WORDS);

        // SHOW
        if (hasProcedure && containsAnyWord(q, SHOW_WORDS) && mentionsProc) {
            // "visa yaml" etc
            if (q.contains("yaml") || q.contains("procedur") || q.contains("procedure")) {
                return Intent.SHOW;
            }
        }

        // CREATE: explicit imperative
        boolean createVerb = containsAnyWord(q, CREATE_WORDS);
        if (createVerb && (mentionsProc || q.contains("om") || q.contains("från") || q.contains("av videon") || q.contains("of the video"))) {
            // require some procedure-ish or video context
            if (hasAttachment || hasProcedure) {
                // "börja om från videon" => CREATE
                if (q.contains("börja om") || q.contains("start over") || q.contains("from scratch") || q.contains("från videon") || q.contains("from the video")) {
                    return Intent.CREATE;
                }
                if (mentionsProc) {
                    return Intent.CREATE;
                }
            }
        }
        // "skapa en procedur av videon"
        if (q.contains("skapa") && q.contains("procedur")) return Intent.CREATE;
        if (q.contains("create") && q.contains("procedure")) return Intent.CREATE;
        if (q.contains("gör en sop") || q.contains("make a sop")) return Intent.CREATE;
        if (q.contains("skriv ner") && q.contains("yaml")) return Intent.CREATE;
        if (q.contains("write") && q.contains("yaml")) return Intent.CREATE;

        // EDIT: procedure exists and change requested
        if (hasProcedure) {
            boolean editVerb = containsAnyWord(q, EDIT_WORDS);
            if (editVerb) return Intent.EDIT;
            // confirmation phrases require last assistant to contain a proposal
            if (containsAnyWord(q, CONFIRM_WORDS) && lastAssistantMessage != null
                    && (lastAssistantMessage.toLowerCase(Locale.ROOT).contains("suggest") 
                        || lastAssistantMessage.toLowerCase(Locale.ROOT).contains("apply")
                        || lastAssistantMessage.toLowerCase(Locale.ROOT).contains("förslag")
                        || lastAssistantMessage.toLowerCase(Locale.ROOT).contains("dela"))) {
                return Intent.EDIT;
            }
        }

        // DISCUSS: procedure exists and question/opinion
        if (hasProcedure) {
            // question marks or why/what/how
            if (q.contains("?") || q.startsWith("varför") || q.startsWith("vad") || q.startsWith("hur")
                    || q.startsWith("why") || q.startsWith("what") || q.startsWith("how")
                    || q.startsWith("är ") || q.startsWith("borde") || q.startsWith("should")) {
                return Intent.DISCUSS;
            }
        }

        // Default: only DISCUSS if the message actually references the procedure/steps
        if (hasProcedure && (q.contains("steg") || q.contains("step") || mentionsProc)) {
            return Intent.DISCUSS;
        }
        return Intent.NONE;
    }

    private static boolean containsAnyWord(String text, Set<String> words) {
        for (String w : words) {
            // word boundary-ish: contains as substring but for multiword ok
            if (text.contains(w)) return true;
        }
        return false;
    }
}
