package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfServiceTest {

    @TempDir
    Path root;

    private PdfService service(String roots) {
        return new PdfService(new ArchiveProperties(roots, root.resolve(".uploads").toString()));
    }

    @Test
    void resolvesPdfInsideRoot() throws IOException {
        Path pdf = Files.writeString(root.resolve("manual.pdf"), "%PDF-1.4");

        Path resolved = service(root.toString()).resolve(pdf.toString());

        assertThat(resolved).isEqualTo(pdf.toAbsolutePath().normalize());
    }

    @Test
    void resolvesPdfInsideUploadDir() throws IOException {
        Path uploads = root.resolve(".uploads");
        Files.createDirectories(uploads);
        Path pdf = Files.writeString(uploads.resolve("uploaded.pdf"), "%PDF-1.4");

        Path resolved = service("").resolve(pdf.toString());

        assertThat(resolved).isEqualTo(pdf.toAbsolutePath().normalize());
    }

    @Test
    void rejectsFileOutsideRoot() throws IOException {
        Path outside = Files.writeString(root.resolveSibling("outside.pdf"), "%PDF-1.4");

        assertThatThrownBy(() -> service(root.toString()).resolve(outside.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPdf() throws IOException {
        Path text = Files.writeString(root.resolve("notes.txt"), "hej");

        assertThatThrownBy(() -> service(root.toString()).resolve(text.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenNoRootConfigured() throws IOException {
        Path pdf = Files.writeString(root.resolve("manual.pdf"), "%PDF-1.4");

        assertThatThrownBy(() -> service("").resolve(pdf.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> service(root.toString()).resolve(root.resolve("nope.pdf").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
