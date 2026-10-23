package se.rightech.fakturummetsarkiv.markdown;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {

    private final MarkdownRenderer markdown = new MarkdownRenderer();

    @Test
    void rendersHeadingsAndLists() {
        String html = markdown.toHtml("# Title\n\n- a\n- b");

        assertThat(html).contains("<h1>Title</h1>");
        assertThat(html).contains("<li>a</li>");
        assertThat(html).contains("<li>b</li>");
    }

    @Test
    void rendersTables() {
        String html = markdown.toHtml("| a | b |\n|---|---|\n| 1 | 2 |");

        assertThat(html).contains("<table>");
        assertThat(html).contains("<th>a</th>");
        assertThat(html).contains("<td>1</td>");
    }

    @Test
    void escapesRawHtml() {
        String html = markdown.toHtml("<script>alert(1)</script>");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void rendersInlineCode() {
        String html = markdown.toHtml("Use `code` here.");

        assertThat(html).contains("<code>code</code>");
    }

    @Test
    void linksKnownCitationsOnly() {
        String html = markdown.toHtml("Claim [1] and [9].", java.util.Set.of(1));

        assertThat(html).contains("class=\"citation\" data-source-number=\"1\" href=\"#source-1\">[1]</a>");
        assertThat(html).contains("[9]").doesNotContain("href=\"#source-9\"");
    }

    @Test
    void doesNotLinkCitationsInsideCode() {
        String html = markdown.toHtml("`arr[1]` and [1].", java.util.Set.of(1));

        assertThat(html).contains("<code>arr[1]</code>");
        assertThat(html).contains("class=\"citation\"").doesNotContain("<code><a");
    }

    @Test
    void blankReturnsEmpty() {
        assertThat(markdown.toHtml(null)).isEmpty();
        assertThat(markdown.toHtml("   ")).isEmpty();
    }
}
