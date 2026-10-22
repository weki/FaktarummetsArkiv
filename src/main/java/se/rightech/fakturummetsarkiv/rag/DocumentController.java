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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
