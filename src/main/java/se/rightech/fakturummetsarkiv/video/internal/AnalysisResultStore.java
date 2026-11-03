package se.rightech.fakturummetsarkiv.video.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sparar delanalyser och slutresultat på disk så att inget arbete går förlorat
 * om ett Ollama-anrop misslyckas eller appen startas om.
 *
 * Struktur:
 *   <video.output-dir>/<datum>_<tid>_<videonamn>/
 *       del-01-av-04.md
 *       del-02-av-04.md
 *       sammanfattning.md
 *       resultat.json
 */
@Service
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class AnalysisResultStore {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultStore.class);
    private static final DateTimeFormatter RUN_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${video.output-dir:analyser}")
    private String outputDir;

    @Value("${video.save-partials:true}")
    private boolean savePartials;

    /**
     * Skapar en mapp för en körning. Returnerar null om sparande är avstängt
     * eller om mappen inte kunde skapas.
     */
    public Path startRun(String originalFilename) {
        if (!savePartials) {
            return null;
        }
        try {
            String name = sanitize(originalFilename);
            Path dir = Paths.get(outputDir).toAbsolutePath()
                    .resolve(LocalDateTime.now().format(RUN_FORMAT) + "_" + name);
            Files.createDirectories(dir);
            log.info("Sparar analysen i {}", dir);
            return dir;
        } catch (Exception e) {
            log.warn("Kunde inte skapa analysmapp: {}", e.getMessage());
            return null;
        }
    }

    public void savePartial(Path runDir, int index, int total, String text) {
        if (runDir == null) {
            return;
        }
        String fileName = String.format("del-%02d-av-%02d.md", index, total);
        write(runDir.resolve(fileName), text);
    }

    public void saveSummary(Path runDir, String summary) {
        if (runDir == null) {
            return;
        }
        write(runDir.resolve("sammanfattning.md"), summary);
    }

    public void saveMetadata(Path runDir, Map<String, Object> metadata) {
        if (runDir == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>(metadata);
            data.put("sparad", LocalDateTime.now().toString());
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            write(runDir.resolve("resultat.json"), json);
        } catch (Exception e) {
            log.warn("Kunde inte spara metadata: {}", e.getMessage());
        }
    }

    private void write(Path file, String text) {
        try {
            Files.writeString(file, text == null ? "" : text);
        } catch (IOException e) {
            log.warn("Kunde inte skriva {}: {}", file, e.getMessage());
        }
    }

    /** Tar bort tecken som inte fungerar i filnamn. */
    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "video";
        }
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
