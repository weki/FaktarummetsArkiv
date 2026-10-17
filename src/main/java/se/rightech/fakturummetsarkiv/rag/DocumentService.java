package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int EMBED_BATCH = 64;
    private static final List<String> DOC_FIELDS =
            List.of("documentId", "source", "title", "path", "page", "createdAt");

    private final OllamaClient llm;
    private final OllamaProperties ollama;
    private final QdrantProperties qdrant;
    private final QdrantClient qdrantClient;
    private final Chunker chunker;
    private final PdfTextExtractor pdfTextExtractor;
    private final FileStore fileStore;

    public DocumentService(OllamaClient llm, OllamaProperties ollama, QdrantProperties qdrant,
                           QdrantClient qdrantClient, Chunker chunker,
                           PdfTextExtractor pdfTextExtractor, FileStore fileStore) {
        this.llm = llm;
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.qdrantClient = qdrantClient;
        this.chunker = chunker;
        this.pdfTextExtractor = pdfTextExtractor;
        this.fileStore = fileStore;
    }

    public record DocumentInfo(String id, String name, int chunks, int characters, String path, Instant createdAt) {
    }

    public record DocumentChunk(Integer page, Integer chunkIndex, String text) {
    }

    public record DocumentDetail(String id, String name, String path, int chunks, int characters,
                                 List<DocumentChunk> content) {
    }

    private record IndexedChunk(Integer page, int index, String text) {
    }

    /** Textinmatning utan fil (sparas inte som original). */
    public DocumentInfo add(String name, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Dokumentet är tomt");
        }
        String title = name == null || name.isBlank() ? "Dokument" : name.trim();
        List<IndexedChunk> chunks = new ArrayList<>();
        int index = 0;
        for (Chunker.Chunk chunk : chunker.chunk(text)) {
            chunks.add(new IndexedChunk(null, index++, chunk.text()));
        }
        return index(title, title, null, chunks, text.length());
    }

    /** Filuppladdning: sparar originalet och indexerar innehållet (PDF eller text). */
    public DocumentInfo addFile(String filename, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Filen är tom");
        }
        String title = filename == null || filename.isBlank() ? "Dokument" : filename.trim();
        Path stored = fileStore.store(title, bytes);
        String path = stored.toAbsolutePath().toString();

        List<IndexedChunk> chunks;
        int characters;
        if (isPdf(title)) {
            chunks = new ArrayList<>();
            characters = 0;
            for (PdfTextExtractor.PdfPage page : pdfTextExtractor.extract(bytes)) {
                int index = 0;
                for (Chunker.Chunk chunk : chunker.chunk(page.text())) {
                    chunks.add(new IndexedChunk(page.page(), index++, chunk.text()));
                }
                characters += page.text().length();
            }
        } else {
            String text = new String(bytes, StandardCharsets.UTF_8);
            chunks = new ArrayList<>();
            int index = 0;
            for (Chunker.Chunk chunk : chunker.chunk(text)) {
                chunks.add(new IndexedChunk(null, index++, chunk.text()));
            }
            characters = text.length();
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Dokumentet innehåller ingen text (kan behöva OCR)");
        }
        return index(title, title, path, chunks, characters);
    }

    private DocumentInfo index(String title, String source, String path,
                               List<IndexedChunk> chunks, int characters) {
        String model = ollama.embeddingModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Ingen embedding-modell konfigurerad (ollama.embedding-model)");
        }

        List<double[]> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            List<String> inputs = chunks.subList(i, Math.min(i + EMBED_BATCH, chunks.size())).stream()
                    .map(chunk -> qdrant.passagePrefix() + chunk.text())
                    .toList();
            List<double[]> batch = llm.embed(model, inputs);
            if (batch.size() != inputs.size()) {
                throw new IllegalStateException("Kunde inte skapa embeddings för alla textstycken");
            }
            vectors.addAll(batch);
        }

        String documentId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        qdrantClient.ensureCollection();

        List<QdrantClient.Point> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            IndexedChunk chunk = chunks.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documentId", documentId);
            payload.put("title", title);
            payload.put("source", source);
            payload.put("createdAt", createdAt);
            if (path != null) {
                payload.put("path", path);
            }
            if (chunk.page() != null) {
                payload.put("page", chunk.page());
            }
            payload.put("chunkIndex", chunk.index());
            payload.put("text", chunk.text());
            points.add(new QdrantClient.Point(UUID.randomUUID().toString(), vectors.get(i), payload));
        }
        qdrantClient.upsert(points);

        log.info("Indexerade '{}' ({} textstycken)", title, chunks.size());
        return new DocumentInfo(documentId, title, chunks.size(), characters, path, Instant.ofEpochMilli(createdAt));
    }

    /** Listar dokumenten som faktiskt finns i vektordatabasen (grupperade per dokument). */
    public List<DocumentInfo> list() {
        List<Map<String, Object>> payloads = qdrantClient.scrollPayloads(qdrant.collection(), DOC_FIELDS);
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Map<String, Object> payload : payloads) {
            String id = firstText(payload.get("documentId"));
            if (id == null) {
                id = firstText(payload.get("source"));
            }
            if (id == null) {
                id = firstText(payload.get("path"));
            }
            if (id == null) {
                id = "okänt";
            }
            String name = firstText(payload.get("title"));
            if (name == null) {
                name = firstText(payload.get("source"));
            }
            final String docName = name == null ? "Dokument" : name;
            Group group = groups.computeIfAbsent(id, key -> new Group(key, docName));
            group.chunks++;
            group.path = firstText(payload.get("path"));
            Number created = asNumber(payload.get("createdAt"));
            if (created != null && created.longValue() > group.createdAt) {
                group.createdAt = created.longValue();
            }
        }
        return groups.values().stream()
                .sorted(Comparator.comparingLong((Group group) -> group.createdAt).reversed())
                .map(group -> new DocumentInfo(group.id, group.name, group.chunks, 0, group.path,
                        group.createdAt > 0 ? Instant.ofEpochMilli(group.createdAt) : null))
                .toList();
    }

    /** Hämtar metadata och allt textinnehåll för ett dokument (matchar documentId eller source). */
    public DocumentDetail detail(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Inget dokument-id angivet");
        }
        Map<String, Object> filter = Map.of("should", List.of(
                Map.of("key", "documentId", "match", Map.of("value", id)),
                Map.of("key", "source", "match", Map.of("value", id))));
        List<Map<String, Object>> payloads = qdrantClient.scrollPayloads(qdrant.collection(),
                List.of("title", "source", "path", "page", "chunkIndex", "text"), filter);
        if (payloads.isEmpty()) {
            throw new IllegalArgumentException("Dokumentet finns inte");
        }
        String name = null;
        String path = null;
        for (Map<String, Object> payload : payloads) {
            if (name == null) {
                name = firstText(payload.get("title"));
                if (name == null) {
                    name = firstText(payload.get("source"));
                }
            }
            if (path == null) {
                path = firstText(payload.get("path"));
            }
        }
        List<DocumentChunk> content = payloads.stream()
                .map(payload -> new DocumentChunk(
                        asInteger(payload.get("page")),
                        asInteger(payload.get("chunkIndex")),
                        payload.get("text") == null ? "" : String.valueOf(payload.get("text"))))
                .sorted(Comparator.comparing(DocumentChunk::page, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DocumentChunk::chunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        int characters = content.stream().mapToInt(chunk -> chunk.text().length()).sum();
        return new DocumentDetail(id, name == null ? "Dokument" : name, path, content.size(), characters, content);
    }

    public List<String> collections() {
        return qdrantClient.listCollections();
    }

    /** Tar bort alles vektorer för ett dokument (matchar documentId eller source). */
    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Inget dokument-id angivet");
        }
        qdrantClient.deleteByMatch(qdrant.collection(), "documentId", id);
        qdrantClient.deleteByMatch(qdrant.collection(), "source", id);
        log.info("Tog bort dokument {} ur kollektionen '{}'", id, qdrant.collection());
    }

    private static final class Group {
        private final String id;
        private final String name;
        private String path;
        private int chunks;
        private long createdAt;

        private Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static String firstText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Number asNumber(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static boolean isPdf(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }
}
