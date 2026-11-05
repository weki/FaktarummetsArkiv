package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandsUnchangedTest {

    @Test
    void unchangedSimple() {
        String a = "metadata:\n  title: T\nsections:\n- steps:\n  - command: \"foo\"\n";
        assertTrue(ChatService.commandsUnchanged(a, a));
    }

    @Test
    void changedCommand() {
        String a = "command: \"foo\"\n";
        String b = "command: \"bar\"\n";
        assertFalse(ChatService.commandsUnchanged(a, b));
    }

    @Test
    void blockScalar() {
        String a = "command: |\n  foo\n  bar\n";
        List<String> cmds = ChatService.extractCommands(a);
        assertFalse(cmds.isEmpty());
    }

    @Test
    void singleQuotes() {
        String a = "command: 'foo bar'\n";
        List<String> cmds = ChatService.extractCommands(a);
        assertTrue(cmds.contains("foo bar"));
    }

    @Test
    void unquoted() {
        String a = "command: foo\n";
        List<String> cmds = ChatService.extractCommands(a);
        assertTrue(cmds.contains("foo"));
    }

    @Test
    void subprocedures() {
        String a = "subprocedures:\n- steps:\n  - command: \"x\"\n";
        List<String> cmds = ChatService.extractCommands(a);
        assertTrue(cmds.contains("x"));
    }

    @Test
    void invalidYamlFallback() {
        String a = "command: \"abc\"\ncommand: \"def\"\n{{invalid";
        List<String> cmds = ChatService.extractCommands(a);
        assertTrue(cmds.contains("abc") && cmds.contains("def"));
    }
}
