package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileStoreTest {

    @TempDir
    Path uploadDir;

    private FileStore store() {
        return new FileStore(new ArchiveProperties("", uploadDir.toString()));
    }

    @Test
    void storesFileInsideUploadDir() throws IOException {
        Path path = store().store("manual.pdf", "%PDF-1.4".getBytes());

        assertThat(path).startsWith(uploadDir.toAbsolutePath().normalize());
        assertThat(Files.exists(path)).isTrue();
        assertThat(path.getFileName().toString()).isEqualTo("manual.pdf");
    }

    @Test
    void sanitizesPathAndWeirdCharacters() throws IOException {
        Path path = store().store("../../etc/my fil (v1).pdf", "x".getBytes());

        assertThat(path).startsWith(uploadDir.toAbsolutePath().normalize());
        assertThat(path.getFileName().toString()).isEqualTo("my_fil__v1_.pdf");
    }

    @Test
    void createsDirectoryWhenMissing() throws IOException {
        Path missing = uploadDir.resolve("arkiv");
        FileStore fileStore = new FileStore(new ArchiveProperties("", missing.toString()));

        Path path = fileStore.store("b.txt", "hej".getBytes());

        assertThat(path).startsWith(missing.toAbsolutePath().normalize());
        assertThat(Files.exists(path)).isTrue();
    }
}
