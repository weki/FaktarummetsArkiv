package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class FileStore {

    private static final Logger log = LoggerFactory.getLogger(FileStore.class);

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
        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return target;
    }

    /** Raderar en sparad originalfil, men bara inom uppladdningsmappen. */
    public boolean delete(Path path) {
        if (path == null) {
            return false;
        }
        Path file = path.toAbsolutePath().normalize();
        Path root = properties.uploadPath();
        if (!file.startsWith(root)) {
            log.warn("Vägrade radera fil utanför uppladdningsmappen: {}", file);
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Kunde inte radera originalfilen {}", file, e);
            return false;
        }
    }

    private static String sanitize(String filename) {
        String name = filename == null || filename.isBlank() ? "dokument" : filename;
        name = name.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("^\\.+", "");
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            name = "dokument";
        }
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, Math.min(dot > 0 ? dot : 120, 120 - extension.length())) + extension;
        }
        return name;
    }
}
