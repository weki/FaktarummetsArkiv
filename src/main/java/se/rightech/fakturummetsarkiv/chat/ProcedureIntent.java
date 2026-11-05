package se.rightech.fakturummetsarkiv.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProcedureIntent {

    public enum Intent { NONE, CREATE, EDIT, DISCUSS, SHOW }

    public static final String PROPOSAL_MARKER = "Say the word and I'll apply it";

    private static final Set<String> PROCEDURE_WORDS = Set.of("procedur", "procedure", "sop", "yaml", "instruktion", "instruction");

    private static final Set<String> CREATE_VERBS = Set.of("skapa", "skriv", "gör", "generera", "ta", "create", "write", "make", "generate", "draft");
    // multiword create: "ta fram", "ta fram"
    private static final Set<String> EDIT_VERBS = Set.of("lägg", "ta", "slå", "dela", "flytta", "ändra", "ändrar", "byt", "uppdatera", "modifiera", "justera", "förenkla", "förkorta", "komplettera", "ersätt", "döp", "numrera", "merge", "split", "move", "change", "rewrite", "update", "modify", "adjust", "shorten", "simplify", "replace", "rename");

    private static final Set<String> QUESTION_WORDS = Set.of("varför", "vad", "hur", "vem", "när", "vilka", "vilket", "är", "ska", "kan", "borde", "bör", "behöver", "why", "what", "how", "who", "when", "which", "is", "should", "can", "does", "do");

    private static final Set<String> CONFIRM_WORDS = Set.of("ja", "japp", "yes", "gör", "kör", "lägg", "do", "apply", "go");

    public static Intent classify(String question, boolean hasProcedure, boolean hasAttachment, String lastAssistantMessage) {
        if (question == null || question.isBlank()) return Intent.NONE;
        String q = question.toLowerCase(Locale.ROOT).trim();
        String[] toks = q.split("[^\\p{L}\\p{N}-]+");
        List<String> tokens = new ArrayList<>();
        for (String t : toks) if (!t.isBlank()) tokens.add(t);

        // SHOW
        if (hasProcedure && containsPhrase(tokens, "visa") && (containsToken(tokens, "yaml") || containsToken(tokens, "procedur") || containsToken(tokens, "procedure"))) return Intent.SHOW;
        if (hasProcedure && containsPhrase(tokens, "skriv") && containsPhrase(tokens, "ut") && (containsToken(tokens, "procedur") || containsToken(tokens, "procedure"))) return Intent.SHOW;
        if (hasProcedure && containsToken(tokens, "show") && (containsToken(tokens, "yaml") || containsToken(tokens, "procedure"))) return Intent.SHOW;

        // CREATE
        boolean createVerb = containsAnyToken(tokens, CREATE_VERBS) || containsPhrase(tokens, "ta", "fram");
        boolean procWord = containsAnyToken(tokens, PROCEDURE_WORDS);
        boolean restart = (containsPhrase(tokens, "börja", "om") || containsPhrase(tokens, "start", "over") || containsPhrase(tokens, "från", "början") || containsPhrase(tokens, "from", "scratch")) 
                && (containsToken(tokens, "videon") || containsToken(tokens, "video") || containsToken(tokens, "bilagan") || containsToken(tokens, "the"));
        if (restart && hasAttachment) return Intent.CREATE;
        if (createVerb && procWord && (hasAttachment || hasProcedure)) return Intent.CREATE;
        // "Kan du skapa en procedur..." 
        if (containsPhrase(tokens, "kan", "du") && createVerb && procWord) return Intent.CREATE;
        if (containsPhrase(tokens, "can", "you") && createVerb && procWord) return Intent.CREATE;

        // Polite edit requests before question control
        boolean polite = containsPhrase(tokens, "kan", "du") || containsPhrase(tokens, "kan", "ni") || containsPhrase(tokens, "skulle", "du", "kunna") || containsPhrase(tokens, "could", "you") || containsPhrase(tokens, "can", "you") || containsPhrase(tokens, "please");
        boolean editVerbPresent = containsAnyToken(tokens, EDIT_VERBS) || containsPhrase(tokens, "lägg", "till") || containsPhrase(tokens, "slå", "ihop") || containsPhrase(tokens, "ta", "bort") || containsPhrase(tokens, "dela", "upp") || containsPhrase(tokens, "skriv", "om") || containsPhrase(tokens, "gör", "kritiskt");
        if (hasProcedure && polite && editVerbPresent) return Intent.EDIT;

        // Question control
        boolean isQuestion = q.endsWith("?") || (!tokens.isEmpty() && QUESTION_WORDS.contains(tokens.get(0)));
        if (isQuestion) {
            if (hasProcedure) {
                // "Borde vi dela upp steg 4?" is a question but edit-ish? Spec says DISCUSS
                return Intent.DISCUSS;
            }
            return Intent.NONE;
        }

        // EDIT
        if (hasProcedure) {
            if (editVerbPresent) return Intent.EDIT;
            // "Gör steg 6 kritiskt"
            if (containsToken(tokens, "gör") && containsToken(tokens, "steg")) {
                // find number after steg
                for (int i=0;i+1<tokens.size();i++) {
                    if (tokens.get(i).equals("steg") && tokens.get(i+1).matches("\\d+")) return Intent.EDIT;
                }
            }
            // "Skriv den för operatörer" / "gör den kortare"
            if ((containsToken(tokens, "skriv") || containsToken(tokens, "gör")) && (containsToken(tokens, "den") || containsToken(tokens, "det") || containsToken(tokens, "proceduren"))) return Intent.EDIT;
            if (containsToken(tokens, "make") && (containsToken(tokens, "it") || containsToken(tokens, "shorter"))) return Intent.EDIT;
            // confirmation
            if (isConfirm(tokens) && lastAssistantMessage != null) {
                String la = lastAssistantMessage.toLowerCase(Locale.ROOT);
                if (la.contains(PROPOSAL_MARKER.toLowerCase(Locale.ROOT)) || la.contains("suggest") || la.contains("förslag")) {
                    return Intent.EDIT;
                } else {
                    return Intent.DISCUSS;
                }
            }
        }

        // DISCUSS if references steps/procedure
        if (hasProcedure && (containsToken(tokens, "steg") || containsToken(tokens, "step") || procWord)) return Intent.DISCUSS;

        return Intent.NONE;
    }

    private static boolean containsToken(List<String> tokens, String w) { return tokens.contains(w); }
    private static boolean containsAnyToken(List<String> tokens, Set<String> set) {
        for (String t : tokens) if (set.contains(t)) return true;
        return false;
    }
    private static boolean containsPhrase(List<String> tokens, String... phrase) {
        if (phrase.length == 0) return false;
        outer: for (int i=0;i<=tokens.size()-phrase.length;i++) {
            for (int j=0;j<phrase.length;j++) {
                if (!tokens.get(i+j).equals(phrase[j])) continue outer;
            }
            return true;
        }
        return false;
    }
    private static boolean isConfirm(List<String> tokens) {
        // whole phrase or single confirm word
        String joined = String.join(" ", tokens);
        for (String c : new String[]{"ja","japp","yes","gör det","kör","kör på","lägg in det","do it","apply it","go ahead"}) {
            if (joined.equals(c) || joined.startsWith(c+" ")) return true;
        }
        return false;
    }
}
