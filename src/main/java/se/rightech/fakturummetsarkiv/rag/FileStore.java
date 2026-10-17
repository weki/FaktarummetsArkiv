package se.rightech.fakturummetsarkiv.rag;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class FileStore {

    private final ArchiveProperties properties;

    public FileStore(ArchiveProperties properties) {
        this.properties = properties;
    }

    /** Sparar filen under arkivmappen och returnerar den absoluta sökvägen. */
    public Path store(String filename, byte[] bytes) throws IOException {
        Path directory = properties.uploadPath();
        Files.createDirectories(directory);
        String safe = sanitize(filename);
        Path target = directory.resolve(UUID.randomUUID() + "-" + safe).normalize();
        if (!target.startsWith(directory)) {
            throw new IllegalArgumentException("Ogiltigt filnamn");
        }
        Files.write(target, bytes);
        return target;
    }

    private static String sanitize(String filename) {
        String name = filename == null || filename.isBlank() ? "dokument" : filename;
        name = name.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
