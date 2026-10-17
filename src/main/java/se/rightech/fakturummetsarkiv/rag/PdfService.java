package se.rightech.fakturummetsarkiv.rag;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

@Service
public class PdfService {

    private final ArchiveProperties properties;

    public PdfService(ArchiveProperties properties) {
        this.properties = properties;
    }

    /** Löser en sökväg som måste ligga inom de tillåtna arkivmapparna och existera. */
    public Path resolveWithinRoots(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Ingen filsökväg angiven");
        }
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Ogiltig filsökväg");
        }
        List<Path> roots = properties.allowedRoots();
        boolean allowed = roots.stream().anyMatch(path::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Filen ligger utanför de tillåtna arkivmapparna");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Filen finns inte");
        }
        return path;
    }

    public Path resolve(String rawPath) {
        Path path = resolveWithinRoots(rawPath);
        if (!path.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Endast PDF-filer kan visas");
        }
        return path;
    }
}
