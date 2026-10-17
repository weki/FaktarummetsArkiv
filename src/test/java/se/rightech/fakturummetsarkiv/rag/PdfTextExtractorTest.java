package se.rightech.fakturummetsarkiv.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsTextPerPage() throws IOException {
        List<PdfTextExtractor.PdfPage> pages = extractor.extract(twoPagePdf());

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).page()).isEqualTo(1);
        assertThat(pages.get(0).text()).contains("Sida 1");
        assertThat(pages.get(1).page()).isEqualTo(2);
        assertThat(pages.get(1).text()).contains("Sida 2");
    }

    @Test
    void skipsBlankPages() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDPage second = new PDPage();
            document.addPage(second);
            try (PDPageContentStream stream = new PDPageContentStream(document, second)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("Endast sida 2");
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }

        List<PdfTextExtractor.PdfPage> pages = extractor.extract(pdf);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).page()).isEqualTo(2);
    }

    private static byte[] twoPagePdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage();
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    stream.newLineAtOffset(72, 700);
                    stream.showText("Sida " + page + " med lite text.");
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
