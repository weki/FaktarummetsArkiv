package se.rightech.fakturummetsarkiv.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PdfTextExtractor {

    public record PdfPage(int page, String text) {
    }

    public List<PdfPage> extract(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PageTextStripper stripper = new PageTextStripper();
            stripper.setSortByPosition(true);
            stripper.strip(document);

            List<PdfPage> pages = new ArrayList<>();
            for (Map.Entry<Integer, StringBuilder> entry : stripper.pages.entrySet()) {
                if (entry.getKey() <= 0) {
                    continue;
                }
                String text = entry.getValue().toString().replaceAll("\\s+", " ").trim();
                if (!text.isEmpty()) {
                    pages.add(new PdfPage(entry.getKey(), text));
                }
            }
            return pages;
        }
    }

    private static final class PageTextStripper extends PDFTextStripper {

        private final Map<Integer, StringBuilder> pages = new LinkedHashMap<>();

        private final Writer sink = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                current().append(cbuf, off, len);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        private PageTextStripper() throws IOException {
            super();
        }

        void strip(PDDocument document) throws IOException {
            writeText(document, sink);
        }

        private StringBuilder current() {
            return pages.computeIfAbsent(getCurrentPageNo(), key -> new StringBuilder());
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            pages.put(getCurrentPageNo(), new StringBuilder());
            super.processPage(page);
        }
    }
}
