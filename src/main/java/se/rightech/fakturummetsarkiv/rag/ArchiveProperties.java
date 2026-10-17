package se.rightech.fakturummetsarkiv.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "archive")
public record ArchiveProperties(
        @DefaultValue("") String pdfRoots,
        @DefaultValue("archive") String uploadDir) {

    public List<String> rootList() {
        List<String> roots = new ArrayList<>();
        if (pdfRoots != null && !pdfRoots.isBlank()) {
            for (String root : pdfRoots.split(",")) {
                if (!root.isBlank()) {
                    roots.add(root.trim());
                }
            }
        }
        return roots;
    }

    public Path uploadPath() {
        return Path.of(uploadDir == null || uploadDir.isBlank() ? "archive" : uploadDir)
                .toAbsolutePath().normalize();
    }

    /** Alla mappar som PDF-filer får läsas från: konfigurerade rötter + uppladdningsmappen. */
    public List<Path> allowedRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        for (String root : rootList()) {
            roots.add(Path.of(root).toAbsolutePath().normalize());
        }
        roots.add(uploadPath());
        return new ArrayList<>(roots);
    }
}
