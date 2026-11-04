package se.rightech.fakturummetsarkiv.procedurebridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/video-jobs")
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoJobController {

    private static final long SSE_TIMEOUT_MS = 1_800_000L;
    private static final Set<String> ALLOWED_EXT = Set.of("mp4", "mkv", "webm", "mov");

    private final VideoJobService jobService;
    private final DocuForgeExporter exporter;

    public VideoJobController(VideoJobService jobService, DocuForgeExporter exporter) {
        this.jobService = jobService;
        this.exporter = exporter;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submit(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "hint", required = false) String hint,
                                    @RequestParam(value = "language", required = false) String language) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ingen videofil skickades med."));
        }
        String filename = file.getOriginalFilename();
        String ext = extension(filename);
        if (!ALLOWED_EXT.contains(ext.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Otillåtet filformat. Tillåtna: " + ALLOWED_EXT));
        }
        Path tempFile;
        try {
            tempFile = Files.createTempFile("video-", "-" + filename);
            file.transferTo(tempFile.toFile());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Kunde inte spara uppladdningen: " + e.getMessage()));
        }
        VideoJob job = jobService.submit(tempFile, filename, hint, language);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.id);
        body.put("filename", filename);
        body.put("status", job.status.name());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (VideoJob job : jobService.list()) {
            out.add(toSummary(job));
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        VideoJob job = jobService.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toSummary(job));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        VideoJob job = jobService.get(id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (job == null) {
            try { emitter.send(SseEmitter.event().name("error").data(Map.of("error", "Jobbet finns inte."))); }
            catch (Exception ignored) { }
            emitter.complete();
            return emitter;
        }
        job.emitters.add(emitter);
        emitter.onCompletion(() -> job.emitters.remove(emitter));
        emitter.onTimeout(() -> job.emitters.remove(emitter));
        // send current state
        try {
            emitter.send(SseEmitter.event().name("status").data(Map.of("status", job.status.name(),
                    "message", job.progressMessage)));
            if (job.status == VideoJob.Status.DONE || job.status == VideoJob.Status.FAILED
                    || job.status == VideoJob.Status.CANCELLED) {
                emitter.complete();
            }
        } catch (Exception ignored) { }
        return emitter;
    }

    @GetMapping("/{id}/draft")
    public ResponseEntity<?> draft(@PathVariable String id) {
        VideoJob job = jobService.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.draft == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Utkastet är inte klart ännu."));
        }
        return ResponseEntity.ok(job.draft);
    }

    @GetMapping("/{id}/frames/{file}")
    public ResponseEntity<Resource> frame(@PathVariable String id, @PathVariable String file) {
        VideoJob job = jobService.get(id);
        if (job == null || job.savedDir == null) return ResponseEntity.notFound().build();
        Path framesDir = job.savedDir.resolve("frames").toAbsolutePath().normalize();
        Path target = framesDir.resolve(file).normalize();
        if (!target.startsWith(framesDir) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(target);
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            String probed = Files.probeContentType(target);
            if (probed != null) type = MediaType.parseMediaType(probed);
        } catch (Exception ignored) { }
        return ResponseEntity.ok().contentType(type).body(resource);
    }

    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> export(@PathVariable String id) {
        VideoJob job = jobService.get(id);
        if (job == null || job.draft == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        byte[] zip;
        try {
            zip = exporter.toZip(job.draft);
        } catch (DocuForgeExporter.ExportException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        }
        org.springframework.core.io.ByteArrayResource resource = new org.springframework.core.io.ByteArrayResource(zip);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"procedur.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelOrDelete(@PathVariable String id) {
        VideoJob job = jobService.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.status == VideoJob.Status.QUEUED || job.status == VideoJob.Status.ANALYZING) {
            boolean ok = jobService.cancel(id);
            if (!ok) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Jobbet kan inte avbrytas."));
            return ResponseEntity.ok(Map.of("id", id, "status", "CANCELLED"));
        }
        boolean ok = jobService.delete(id);
        if (!ok) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Jobbet kunde inte raderas."));
        return ResponseEntity.ok(Map.of("id", id, "status", "DELETED"));
    }

    private Map<String, Object> toSummary(VideoJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.id);
        m.put("filename", job.filename);
        m.put("status", job.status.name());
        m.put("progressMessage", job.progressMessage);
        m.put("createdAt", job.createdAt.toString());
        m.put("finishedAt", job.finishedAt == null ? null : job.finishedAt.toString());
        m.put("error", job.error);
        m.put("savedDir", job.savedDir == null ? null : job.savedDir.toString());
        m.put("runId", job.runId);
        return m;
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }
}
