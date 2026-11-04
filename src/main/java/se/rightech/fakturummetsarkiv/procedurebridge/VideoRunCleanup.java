package se.rightech.fakturummetsarkiv.procedurebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoRunCleanup {

    private static final Logger log = LoggerFactory.getLogger(VideoRunCleanup.class);

    private final VideoRunRepository repo;
    private final int keepDays;

    public VideoRunCleanup(VideoRunRepository repo,
                           @Value("${video.keep-runs-days:30}") int keepDays) {
        this.repo = repo;
        this.keepDays = keepDays;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 24 * 60 * 60 * 1000L)
    public void cleanup() {
        if (keepDays <= 0) return;
        Instant cutoff = Instant.now().minus(keepDays, ChronoUnit.DAYS);
        int removed = 0;
        for (String runId : repo.listRuns()) {
            // runId starts with timestamp yyyyMMdd-HHmmss; parse best-effort via directory mtime
            Path dir = repo.getOutputDir().resolve(runId);
            try {
                Instant mtime = Files.getLastModifiedTime(dir).toInstant();
                if (mtime.isBefore(cutoff)) {
                    if (repo.delete(runId)) removed++;
                }
            } catch (Exception e) {
                log.warn("Kunde inte kontrollera ålder för {}: {}", runId, e.getMessage());
            }
        }
        if (removed > 0) log.info("Rensade {} gamla videokörningar (äldre än {} dagar).", removed, keepDays);
    }
}
