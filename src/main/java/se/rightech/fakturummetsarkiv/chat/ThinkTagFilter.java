package se.rightech.fakturummetsarkiv.chat;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * En gemensam tillståndsmaskin för &lt;think&gt;-taggar. Buffrar ofullständiga taggar så att
 * taggar som delas mellan tokens inte läcker ut i svaret eller tankeströmmen.
 */
public final class ThinkTagFilter {

    private static final String OPEN = "<think";
    private static final String CLOSE = "</think";

    /** Tar bort kompletta och oavslutade think-block från ett färdigt (icke-strömmande) svar. */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text.replaceAll("(?is)<think(?:\\s[^>]*)?>.*?</think\\s*>", "");
        int open = result.toLowerCase(Locale.ROOT).lastIndexOf(OPEN);
        if (open >= 0) {
            result = result.substring(0, open);
        }
        result = result.replaceAll("(?is)</think\\s*>", "");
        return result.trim();
    }

    private final Consumer<String> visible;
    private final Consumer<String> thought;
    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder thoughtText = new StringBuilder();
    private boolean thinking;
    private boolean emittedVisible;

    public ThinkTagFilter(Consumer<String> visible, Consumer<String> thought) {
        this.visible = visible == null ? text -> { } : visible;
        this.thought = thought == null ? text -> { } : thought;
    }

    public void accept(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        buffer.append(text);
        process(false);
    }

    public void finish() {
        process(true);
        if (buffer.length() > 0) {
            if (thinking) {
                emitThought(buffer.toString());
            } else {
                emitVisible(buffer.toString());
            }
            buffer.setLength(0);
        }
        // Ett oavslutat <think> får inte lämna ett tomt svar: använd tankeinnehållet som svar.
        if (!emittedVisible && thoughtText.length() > 0) {
            emitVisible(thoughtText.toString());
        }
    }

    private void process(boolean atEnd) {
        while (buffer.length() > 0) {
            if (thinking) {
                int lt = buffer.indexOf("<");
                if (lt < 0) {
                    emitThought(buffer.toString());
                    buffer.setLength(0);
                    return;
                }
                if (lt > 0) {
                    emitThought(buffer.substring(0, lt));
                    buffer.delete(0, lt);
                }
                String lower = buffer.toString().toLowerCase(Locale.ROOT);
                if (lower.startsWith(CLOSE)) {
                    int gt = buffer.indexOf(">");
                    if (gt < 0) {
                        if (!atEnd) {
                            return;
                        }
                        emitThought("<");
                        buffer.deleteCharAt(0);
                        continue;
                    }
                    thinking = false;
                    buffer.delete(0, gt + 1);
                    continue;
                }
                if (!atEnd && CLOSE.startsWith(lower)) {
                    return;
                }
                emitThought("<");
                buffer.deleteCharAt(0);
            } else {
                int lt = buffer.indexOf("<");
                if (lt < 0) {
                    emitVisible(buffer.toString());
                    buffer.setLength(0);
                    return;
                }
                if (lt > 0) {
                    emitVisible(buffer.substring(0, lt));
                    buffer.delete(0, lt);
                }
                String lower = buffer.toString().toLowerCase(Locale.ROOT);
                if (lower.startsWith(CLOSE)) {
                    int gt = buffer.indexOf(">");
                    if (gt < 0) {
                        if (!atEnd) {
                            return;
                        }
                        emitVisible("<");
                        buffer.deleteCharAt(0);
                        continue;
                    }
                    buffer.delete(0, gt + 1);
                    continue;
                }
                if (lower.startsWith(OPEN)) {
                    int gt = buffer.indexOf(">");
                    if (gt < 0) {
                        if (!atEnd) {
                            return;
                        }
                        emitVisible("<");
                        buffer.deleteCharAt(0);
                        continue;
                    }
                    thinking = true;
                    buffer.delete(0, gt + 1);
                    continue;
                }
                if (!atEnd && (OPEN.startsWith(lower) || CLOSE.startsWith(lower))) {
                    return;
                }
                emitVisible("<");
                buffer.deleteCharAt(0);
            }
        }
    }

    private void emitVisible(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        emittedVisible = true;
        visible.accept(text);
    }

    private void emitThought(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        thoughtText.append(text);
        thought.accept(text);
    }
}
