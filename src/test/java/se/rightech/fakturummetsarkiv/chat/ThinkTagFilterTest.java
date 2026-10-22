package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkTagFilterTest {

    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder thought = new StringBuilder();
    private final ThinkTagFilter filter = new ThinkTagFilter(visible::append, thought::append);

    @Test
    void separatesThinkingFromVisibleText() {
        filter.accept("<think>fundera</think>Hej");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("Hej");
        assertThat(thought.toString()).isEqualTo("fundera");
    }

    @Test
    void buffersTagsSplitAcrossTokens() {
        filter.accept("<thi");
        filter.accept("nk>hemlig</thi");
        filter.accept("nk>svar");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("svar");
        assertThat(thought.toString()).isEqualTo("hemlig");
    }

    @Test
    void supportsAttributesInThinkTag() {
        filter.accept("<think type=\"reasoning\">tanke</think>svar");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("svar");
        assertThat(thought.toString()).isEqualTo("tanke");
    }

    @Test
    void keepsVisibleTextBeforeUnclosedThinking() {
        filter.accept("svar<think>tanke");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("svar");
        assertThat(thought.toString()).isEqualTo("tanke");
    }

    @Test
    void promotesUnclosedThinkingWhenNoVisibleTextExists() {
        filter.accept("<think>tanke");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("tanke");
    }

    @Test
    void dropsStrayClosingTag() {
        filter.accept("svar</think> mer");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("svar mer");
    }

    @Test
    void passesPlainTextThrough() {
        filter.accept("Hej ");
        filter.accept("varlden");
        filter.finish();

        assertThat(visible.toString()).isEqualTo("Hej varlden");
        assertThat(thought.toString()).isEmpty();
    }
}
