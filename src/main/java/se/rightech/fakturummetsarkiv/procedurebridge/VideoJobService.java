package se.rightech.fakturummetsarkiv.procedurebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import se.rightech.fakturummetsarkiv.video.VideoDescriber;
import se.rightech.fakturummetsarkiv.video.VideoOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoJobService {

    private static final Logger log = LoggerFactory.getLogger(VideoJobService.class);

    private final VideoDescriber describer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-job");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, VideoJob> jobs = new ConcurrentHashMap<>();

    public VideoJobService(VideoDescriber describer) {
        this.describer = describer;
    }

    public VideoJob submit(Path videoFile, String originalFilename, String hint, String language) {
        VideoJob job = new VideoJob(UUID.randomUUID().toString(), originalFilename);
        jobs.put(job.id, job);
        VideoOptions options = new VideoOptions(language, hint);
        executor.submit(() -> run(job, videoFile, originalFilename, options));
        return job;
    }

    private void run(VideoJob job, Path videoFile, String originalFilename, VideoOptions options) {
        job.status = VideoJob.Status.ANALYZING;
        job.progressMessage = "Startar analys...";
        job.send("status", Map.of("status", job.status.name(), "message", job.progressMessage));
        try {
            var draft = describer.draft(videoFile, originalFilename, options, msg -> {
                job.progressMessage = msg;
                job.send("progress", msg);
            });
            job.draft = draft;
            job.savedDir = draft.savedDir();
            job.status = VideoJob.Status.DONE;
            job.progressMessage = "Klar";
            job.finishedAt = java.time.Instant.now();
            job.send("result", Map.of("status", job.status.name(), "savedDir",
                    job.savedDir == null ? "" : job.savedDir.toString()));
        } catch (Exception e) {
            log.error("Videojobb {} misslyckades: {}", job.id, e.getMessage(), e);
            job.status = VideoJob.Status.FAILED;
            job.error = e.getMessage();
            job.finishedAt = java.time.Instant.now();
            job.send("error", Map.of("error", job.error == null ? "okänt fel" : job.error));
        } finally {
            try { Files.deleteIfExists(videoFile); } catch (Exception ignored) { }
            job.send("done", job.status.name());
            // close emitters
            for (var e : job.emitters) { try { e.complete(); } catch (Exception ignored) { } }
            job.emitters.clear();
        }
    }

    public VideoJob get(String id) { return jobs.get(id); }

    public java.util.Collection<VideoJob> list() { return jobs.values(); }

    public boolean cancel(String id) {
        VideoJob job = jobs.get(id);
        if (job == null) return false;
        if (job.status == VideoJob.Status.QUEUED || job.status == VideoJob.Status.ANALYZING) {
            job.status = VideoJob.Status.CANCELLED;
            job.finishedAt = java.time.Instant.now();
            job.send("status", Map.of("status", job.status.name()));
            return true;
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }
}
