package se.rightech.fakturummetsarkiv.procedurebridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/procedures")
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class ProcedureExportController {

    private final ProcedureSchemaValidator validator;
    private final VideoRunRepository repo;
    private final DocuForgeExporter exporter;

    public ProcedureExportController(ProcedureSchemaValidator validator, VideoRunRepository repo, DocuForgeExporter exporter) {
        this.validator = validator;
        this.repo = repo;
        this.exporter = exporter;
    }

    public record ExportRequest(String yaml, List<String> runIds, String format) {
    }

    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody ExportRequest request, @RequestParam(value = "format", required = false) String formatParam) {
        String yaml = request == null ? null : request.yaml();
        List<String> runIds = (request == null || request.runIds() == null) ? List.of() : request.runIds();
        String format = (request != null && request.format() != null) ? request.format() : formatParam;

        // Collect available frames
        List<Path> availableFrames = new ArrayList<>();
        for (String rid : runIds) {
            if (!repo.isValidRunId(rid)) continue;
            for (String f : repo.listFrames(rid)) {
                Path p = repo.resolveFrame(rid, f);
                if (p != null) availableFrames.add(p);
            }
        }

        var vr = validator.validate(yaml, availableFrames);
        if (!vr.valid()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("errors", vr.errors()));
        }

        // Re-serialize? Use validated text as-is (yaml param). Build zip manually with yaml + frames referenced.
        if ("yaml".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/yaml"))
                    .body(new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        // Build zip
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("procedure.yaml"));
            zos.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
            // add referenced frames
            java.util.Set<String> referenced = new java.util.HashSet<>();
            // parse referenced files from yaml tree
            if (vr.parsedTree() != null) collectReferencedFiles(vr.parsedTree(), referenced);
            for (Path frame : availableFrames) {
                String name = frame.getFileName().toString();
                if (referenced.isEmpty() || referenced.contains(name)) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(name));
                    zos.write(java.nio.file.Files.readAllBytes(frame));
                    zos.closeEntry();
                }
            }
            zos.finish();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"procedur.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(new ByteArrayResource(baos.toByteArray()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void collectReferencedFiles(com.fasterxml.jackson.databind.JsonNode node, java.util.Set<String> out) {
        if (node == null) return;
        if (node.has("images") && node.get("images").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode img : node.get("images")) {
                String f = img.path("file").asText("");
                if (!f.isBlank()) out.add(f);
            }
        }
        node.fields().forEachRemaining(e -> collectReferencedFiles(e.getValue(), out));
        if (node.isArray()) for (com.fasterxml.jackson.databind.JsonNode n : node) collectReferencedFiles(n, out);
    }
}
