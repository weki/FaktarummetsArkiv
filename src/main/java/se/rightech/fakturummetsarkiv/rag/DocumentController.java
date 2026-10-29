package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final PdfService pdfService;

    public DocumentController(DocumentService documentService, PdfService pdfService) {
        this.documentService = documentService;
        this.pdfService = pdfService;
    }

    public record CreateCollectionRequest(String name) {
    }

    @GetMapping
    public List<DocumentService.DocumentInfo> list() {
        return documentService.list();
    }

    @GetMapping("/collections")
    public List<String> collections() {
        return documentService.collections();
    }

    @GetMapping("/tags")
    public List<String> tags() {
        return documentService.tags();
    }

    @PostMapping(path = "/collections", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<String> createCollection(@RequestBody CreateCollectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Ingen kollektion angiven");
        }
        documentService.createCollection(request.name());
        return documentService.collections();
    }

    @PostMapping(path = "/{id}/collection", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> updateCollection(@PathVariable String id, @RequestBody CollectionRequest request) {
        documentService.updateCollection(id, request == null ? null : request.collection());
        return Map.of("updated", true);
    }

    public record CollectionRequest(String collection) {
    }

    @PostMapping(path = "/{id}/tags", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> updateTags(@PathVariable String id, @RequestBody TagsRequest request) {
        documentService.updateTags(id, request == null ? List.of() : request.tags());
        return Map.of("updated", true);
    }

    public record TagsRequest(List<String> tags) {
    }

    @GetMapping("/{id}")
    public DocumentService.DocumentDetail detail(@PathVariable String id) {
        return documentService.detail(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        DocumentService.DocumentDetail detail = documentService.detail(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(detail.name(), StandardCharsets.UTF_8)
                .build();
        if (detail.path() != null && !detail.path().isBlank()) {
            Path file = pdfService.resolveWithinRoots(detail.path());
            MediaType type = MediaType.APPLICATION_OCTET_STREAM;
            try {
                String probed = Files.probeContentType(file);
                if (probed != null) {
                    type = MediaType.parseMediaType(probed);
                }
            } catch (Exception ignored) {
                // faller tillbaka på application/octet-stream
            }
            return ResponseEntity.ok()
                    .contentType(type)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(new FileSystemResource(file));
        }
        String text = detail.content().stream()
                .map(DocumentService.DocumentChunk::text)
                .collect(Collectors.joining("\n\n"));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ContentDisposition textDisposition = ContentDisposition.attachment()
                .filename(detail.name() + ".txt", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, textDisposition.toString())
                .body(new ByteArrayResource(bytes));
    }

    public record IdsRequest(List<String> ids) {
    }

    /** Sänder ned valda dokument som en zip-fil (originalfil eller text om ingen fil finns). */
    @PostMapping(path = "/download-zip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> downloadZip(@RequestBody IdsRequest request) {
        List<String> ids = request == null || request.ids() == null ? List.of()
                : request.ids().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Inga dokument valda");
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("fakturummets-arkiv.zip", StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = out -> {
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out));
            Set<String> usedNames = new HashSet<>();
            for (String id : ids) {
                DocumentService.DocumentDetail detail;
                try {
                    detail = documentService.detail(id);
                } catch (RuntimeException ignored) {
                    continue; // dokument borttaget under tiden
                }
                byte[] data;
                String name;
                if (detail.path() != null && !detail.path().isBlank()) {
                    Path file = pdfService.resolveWithinRoots(detail.path());
                    data = Files.readAllBytes(file);
                    name = detail.name();
                } else {
                    String text = detail.content().stream()
                            .map(DocumentService.DocumentChunk::text)
                            .collect(Collectors.joining("\n\n"));
                    data = text.getBytes(StandardCharsets.UTF_8);
                    name = detail.name().toLowerCase().endsWith(".txt") ? detail.name() : detail.name() + ".txt";
                }
                zip.putNextEntry(new ZipEntry(uniqueZipName(usedNames, name)));
                zip.write(data);
                zip.closeEntry();
            }
            zip.finish();
            zip.flush();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
    }

    private static String uniqueZipName(Set<String> used, String rawName) {
        String name = rawName == null ? "" : rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank()) {
            name = "dokument";
        }
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        while (!used.add(base + " (" + i + ")" + ext)) {
            i++;
        }
        return base + " (" + i + ")" + ext;
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<Resource> pdf(@PathVariable String id,
                                        @RequestParam(name = "download", defaultValue = "false") boolean download) {
        DocumentService.DocumentDetail detail = documentService.detail(id);
        if (detail.path() == null || detail.path().isBlank()) {
            throw new IllegalArgumentException("Dokumentet har ingen PDF-fil");
        }
        Path file = pdfService.resolve(detail.path());
        Resource resource = new FileSystemResource(file);
        ContentDisposition disposition = ContentDisposition.builder(download ? "attachment" : "inline")
                .filename(detail.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DocumentService.DocumentInfo> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(name = "collection", required = false) String collection,
            @RequestParam(name = "tags", required = false) List<String> tags) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Inga filer angivna");
        }
        List<DocumentService.DocumentInfo> added = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            added.add(documentService.addFile(file.getOriginalFilename(), file.getBytes(), collection, tags));
        }
        if (added.isEmpty()) {
            throw new IllegalArgumentException("Inga filer angivna");
        }
        return added;
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        documentService.delete(id);
        return Map.of("deleted", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler({RestClientException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleUnavailable(RuntimeException ex) {
        log.warn("Tjänstefel vid dokumentanrop", ex);
        return Map.of("error", "Tjänsten är inte tillgänglig just nu");
    }
}
