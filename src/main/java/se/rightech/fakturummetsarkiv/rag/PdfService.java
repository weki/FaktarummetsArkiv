package se.rightech.fakturummetsarkiv.rag;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Service
public class PdfService {

    private final ArchiveProperties properties;

    public PdfService(ArchiveProperties properties) {
        this.properties = properties;
        for (Path root : properties.allowedRoots()) {
            if (root.toString().isBlank() || root.getParent() == null) {
                throw new IllegalStateException("Ogiltig arkivrot: " + root + " (får inte vara / eller tom)");
            }
        }
    }

    /** Löser en sökväg som måste ligga inom de tillåtna arkivmapparna och existera. */
    public Path resolveWithinRoots(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Ingen filsökväg angiven");
        }
        Path candidate;
        try {
            candidate = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Filen finns inte");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Filen finns inte");
        }
        boolean allowed = false;
        for (Path root : properties.allowedRoots()) {
            try {
                if (real.startsWith(root.toRealPath())) {
                    allowed = true;
                    break;
                }
            } catch (IOException ignored) {
                // Roten finns inte (t.ex. uppladdningsmappen före första uppladdningen)
            }
        }
        if (!allowed || !Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Filen finns inte");
        }
        return real;
    }

    public Path resolve(String rawPath) {
        Path path = resolveWithinRoots(rawPath);
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Endast PDF-filer kan visas");
        }
        return path;
    }
}
