package se.rightech.fakturummetsarkiv.markdown;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MarkdownRenderer {

    private static final Pattern HTML_SEGMENT =
            Pattern.compile("(?s)<pre.*?</pre>|<code.*?</code>|<[^>]+>");
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create()));
        options.set(HtmlRenderer.ESCAPE_HTML, true);
        options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");
        options.set(HtmlRenderer.GENERATE_HEADER_ID, false);
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    public String toHtml(String markdown) {
        return toHtml(markdown, Set.of());
    }

    /** Renderar markdown och gör [n] till länkar när n är en källa i svaret. */
    public String toHtml(String markdown, Set<Integer> sourceNumbers) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String html = renderer.render(parser.parse(markdown));
        return sourceNumbers == null || sourceNumbers.isEmpty()
                ? html
                : linkCitations(html, sourceNumbers);
    }

    private static String linkCitations(String html, Set<Integer> sourceNumbers) {
        StringBuilder out = new StringBuilder(html.length());
        Matcher segments = HTML_SEGMENT.matcher(html);
        int last = 0;
        while (segments.find()) {
            out.append(linkPlain(html.substring(last, segments.start()), sourceNumbers));
            out.append(segments.group());
            last = segments.end();
        }
        out.append(linkPlain(html.substring(last), sourceNumbers));
        return out.toString();
    }

    private static String linkPlain(String text, Set<Integer> sourceNumbers) {
        Matcher matcher = CITATION.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            int number;
            try {
                number = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                number = -1;
            }
            String replacement = sourceNumbers.contains(number)
                    ? "<a class=\"citation\" data-source-number=\"" + number + "\" href=\"#source-" + number + "\">["
                            + number + "]</a>"
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
