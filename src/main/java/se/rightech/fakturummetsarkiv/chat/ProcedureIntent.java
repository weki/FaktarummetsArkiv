package se.rightech.fakturummetsarkiv.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProcedureIntent {

    public enum Intent { NONE, CREATE, EDIT, DISCUSS, SHOW }

    public static final String PROPOSAL_MARKER = "Say the word and I'll apply it";

    // Stammar för substantiv (matchar bestämd/obestämd form)
    private static final List<String> PROCEDURE_STEMS = List.of("procedur", "procedure", "instruktion", "sop");
    private static final Set<String> YAML_TOKENS = Set.of("yaml");

    // Verbformer (explicita, inga stammar)
    private static final Set<String> CREATE_VERBS = Set.of("skapa", "skriver", "skriv", "gör", "generera", "create", "write", "make", "generate", "draft");
    private static final Set<String> EDIT_VERBS = Set.of("lägg", "lägga", "lägger", "ändra", "ändrar", "byt", "byter", "dela", "delar", "flytta", "flyttar", "slå", "slår", "uppdatera", "modifiera", "justera", "förenkla", "förkorta", "komplettera", "ersätt", "döp", "numrera", "merge", "split", "move", "change", "rewrite", "update", "modify", "adjust", "shorten", "simplify", "replace", "rename");
    private static final Set<String> SHOW_VERBS = Set.of("visa", "skriv", "write", "show", "display", "print");

    private static final Set<String> QUESTION_WORDS = Set.of("varför", "vad", "hur", "vem", "när", "vilka", "vilket", "är", "ska", "kan", "borde", "bör", "behöver", "why", "what", "how", "who", "when", "which", "is", "should", "can", "does", "do");

    // Bekräftelsefraser (hela fraser)
    private static final Set<String> CONFIRM_PHRASES = Set.of("ja", "japp", "yes", "gör det", "kör", "kör på", "lägg in det", "do it", "apply it", "go ahead");

    // Avslag (hela fraser)
    private static final Set<String> REJECT_PHRASES = Set.of("nej", "nej tack", "no", "skip", "inte nu", "låt bli");

    // Artighetsfraser
    private static final Set<String> POLITE_PHRASES = Set.of("kan du", "kan ni", "skulle du kunna", "vill du", "could you", "can you", "would you", "please");

    public static Intent classify(String question, boolean hasProcedure, boolean hasAttachment, String lastAssistantMessage) {
        if (question == null || question.isBlank()) return Intent.NONE;
        String q = question.toLowerCase(Locale.ROOT).trim();
        String[] toks = q.split("[^\\p{L}\\p{N}-]+");
        List<String> tokens = new ArrayList<>();
        for (String t : toks) if (!t.isBlank()) tokens.add(t);

        if (tokens.isEmpty()) return Intent.NONE;

        // (1) SHOW: visa/skriv ut/show/print + proceduren/yaml/procedur
        if (hasProcedure && hasShowVerb(tokens) && hasProcedureWord(tokens)) return Intent.SHOW;

        // (2) CREATE: skapa/gör/skriv + procedurord + (hasAttachment || hasProcedure)
        boolean createVerb = hasAnyToken(tokens, CREATE_VERBS) || containsPhrase(tokens, "ta", "fram");
        boolean procWord = hasProcedureWord(tokens);
        boolean restart = (containsPhrase(tokens, "börja", "om") || containsPhrase(tokens, "start", "over") || containsPhrase(tokens, "från", "början") || containsPhrase(tokens, "from", "scratch"))
                && (containsToken(tokens, "videon") || containsToken(tokens, "video") || containsToken(tokens, "bilagan") || containsToken(tokens, "the"));
        if (restart && hasAttachment) return Intent.CREATE;
        if (createVerb && procWord && (hasAttachment || hasProcedure)) return Intent.CREATE;
        if (containsPhrase(tokens, "kan", "du") && createVerb && procWord) return Intent.CREATE;
        if (containsPhrase(tokens, "can", "you") && createVerb && procWord) return Intent.CREATE;

        // (3) Artighetsfraser + redigeringsverb -> EDIT (före frågekontroll)
        boolean polite = hasPolitePhrase(tokens);
        boolean editVerbPresent = hasAnyToken(tokens, EDIT_VERBS) || containsPhrase(tokens, "lägg", "till") || containsPhrase(tokens, "slå", "ihop") || containsPhrase(tokens, "ta", "bort") || containsPhrase(tokens, "dela", "upp") || containsPhrase(tokens, "skriv", "om") || containsPhrase(tokens, "gör", "kritiskt");
        if (hasProcedure && polite && editVerbPresent) return Intent.EDIT;

        // (4) Frågekontroll -> DISCUSS med procedur, annars NONE
        boolean isQuestion = q.endsWith("?") || (!tokens.isEmpty() && QUESTION_WORDS.contains(tokens.get(0)));
        if (isQuestion) {
            if (hasProcedure) return Intent.DISCUSS;
            return Intent.NONE;
        }

        // (5) Avslag med procedur -> DISCUSS
        if (hasProcedure && isRejection(tokens)) return Intent.DISCUSS;

        // (6) Bekräftelse med procedur -> EDIT om förslagsmarkör, annars DISCUSS
        if (hasProcedure && isConfirmation(tokens)) {
            if (lastAssistantMessage != null && lastAssistantMessage.toLowerCase(Locale.ROOT).contains(PROPOSAL_MARKER.toLowerCase(Locale.ROOT))) {
                return Intent.EDIT;
            }
            return Intent.DISCUSS;
        }

        // (7) EDIT: redigeringsverb + procedur
        if (hasProcedure) {
            if (editVerbPresent) return Intent.EDIT;
            // "Gör steg 6 kritiskt"
            if (containsToken(tokens, "gör") && containsToken(tokens, "steg")) {
                for (int i=0;i+1<tokens.size();i++) {
                    if (tokens.get(i).equals("steg") && tokens.get(i+1).matches("\\d+")) return Intent.EDIT;
                }
            }
            // "Skriv den för operatörer" / "gör den kortare"
            if ((containsToken(tokens, "skriv") || containsToken(tokens, "gör")) && (containsToken(tokens, "den") || containsToken(tokens, "det") || containsToken(tokens, "proceduren"))) return Intent.EDIT;
            if (containsToken(tokens, "make") && (containsToken(tokens, "it") || containsToken(tokens, "shorter"))) return Intent.EDIT;
        }

        // (8) DISCUSS vid referens till steg/procedur
        if (hasProcedure && (containsToken(tokens, "steg") || containsToken(tokens, "step") || procWord)) return Intent.DISCUSS;

        return Intent.NONE;
    }

    private static boolean hasShowVerb(List<String> tokens) {
        return hasAnyToken(tokens, SHOW_VERBS) || containsPhrase(tokens, "skriv", "ut");
    }

    private static boolean hasProcedureWord(List<String> tokens) {
        // Stam-matchning för substantiv
        for (String token : tokens) {
            for (String stem : PROCEDURE_STEMS) {
                if (token.equals(stem) || token.startsWith(stem + "en") || token.startsWith(stem + "er") || token.startsWith(stem + "erna")) {
                    return true;
                }
            }
        }
        // YAML och SOP
        if (containsAnyToken(tokens, YAML_TOKENS)) return true;
        if (containsToken(tokens, "sop") || containsToken(tokens, "sopen") || containsToken(tokens, "sops")) return true;
        return false;
    }

    private static boolean hasPolitePhrase(List<String> tokens) {
        for (String phrase : POLITE_PHRASES) {
            String[] words = phrase.split("\\s+");
            if (containsPhrase(tokens, words)) return true;
        }
        return false;
    }

    private static boolean isConfirmation(List<String> tokens) {
        String joined = String.join(" ", tokens);
        for (String phrase : CONFIRM_PHRASES) {
            if (joined.equals(phrase) || joined.startsWith(phrase + " ")) return true;
        }
        return false;
    }

    private static boolean isRejection(List<String> tokens) {
        String joined = String.join(" ", tokens);
        for (String phrase : REJECT_PHRASES) {
            if (joined.equals(phrase) || joined.startsWith(phrase + " ")) return true;
        }
        return false;
    }

    private static boolean containsToken(List<String> tokens, String w) { return tokens.contains(w); }
    private static boolean hasAnyToken(List<String> tokens, Set<String> set) {
        for (String t : tokens) if (set.contains(t)) return true;
        return false;
    }
    private static boolean containsAnyToken(List<String> tokens, Set<String> set) {
        return hasAnyToken(tokens, set);
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
}
