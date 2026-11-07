package se.rightech.fakturummetsarkiv.procedurebridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/video-runs")
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class VideoRunController {

    private final VideoRunRepository repo;

    public VideoRunController(VideoRunRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> get(@PathVariable String runId) {
        if (!repo.isValidRunId(runId)) return ResponseEntity.badRequest().build();
        var analysis = repo.findAnalysis(runId);
        if (analysis == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(analysis);
    }

    /**
     * @deprecated Använd {@link #get(String)} istället.
     * Alias för bakåtkompatibilitet.
     */
    @Deprecated
    @GetMapping("/{runId}/draft")
    public ResponseEntity<?> draft(@PathVariable String runId) {
        return get(runId);
    }

    @GetMapping("/{runId}/frames/{file}")
    public ResponseEntity<Resource> frame(@PathVariable String runId, @PathVariable String file) {
        Path target = repo.resolveFrame(runId, file);
        if (target == null) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(target);
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            String probed = Files.probeContentType(target);
            if (probed != null) type = MediaType.parseMediaType(probed);
        } catch (Exception ignored) { }
        return ResponseEntity.ok().contentType(type).body(resource);
    }

    @DeleteMapping("/{runId}")
    public ResponseEntity<?> delete(@PathVariable String runId) {
        if (!repo.isValidRunId(runId)) return ResponseEntity.badRequest().build();
        boolean ok = repo.delete(runId);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().build();
    }
}
