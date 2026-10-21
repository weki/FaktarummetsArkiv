package se.rightech.fakturummetsarkiv.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int EMBED_BATCH = 16;
    /** Antal punkter per upsert-anrop mot Qdrant (håller varje request långt under 32 MB). */
    private static final int UPSERT_BATCH = 64;
    private static final List<String> DOC_FIELDS =
            List.of("documentId", "source", "title", "path", "page", "createdAt", "collection", "tags");

    private final OllamaClient llm;
    private final OllamaProperties ollama;
    private final QdrantProperties qdrant;
    private final QdrantClient qdrantClient;
    private final Chunker chunker;
    private final PdfTextExtractor pdfTextExtractor;
    private final FileStore fileStore;
    private final SettingsStore settingsStore;

    public DocumentService(OllamaClient llm, OllamaProperties ollama, QdrantProperties qdrant,
                           QdrantClient qdrantClient, Chunker chunker,
                           PdfTextExtractor pdfTextExtractor, FileStore fileStore,
                           SettingsStore settingsStore) {
        this.llm = llm;
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.qdrantClient = qdrantClient;
        this.chunker = chunker;
        this.pdfTextExtractor = pdfTextExtractor;
        this.fileStore = fileStore;
        this.settingsStore = settingsStore;
    }

    public record DocumentInfo(String id, String name, int chunks, int characters, String path,
                               String collection, List<String> tags, Instant createdAt, boolean skipped) {
    }

    public record DocumentChunk(Integer page, Integer chunkIndex, String text) {
    }

    public record DocumentDetail(String id, String name, String path, String collection, List<String> tags,
                                 int chunks, int characters, List<DocumentChunk> content) {
    }

    private record IndexedChunk(Integer page, int index, String text) {
    }

    /** Filuppladdning: sparar originalet och indexerar innehållet (PDF eller text). */
    public DocumentInfo addFile(String filename, byte[] bytes, String collection, List<String> tags)
            throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Filen är tom");
        }
        String title = filename == null || filename.isBlank() ? "Dokument" : filename.trim();
        String target = resolveCollection(collection);
        List<String> cleanTags = cleanTags(tags);

        for (DocumentInfo existing : list()) {
            if (existing.name() != null && existing.name().trim().equalsIgnoreCase(title)) {
                log.info("Dokumentet '{}' finns redan i arkivet – hoppar över uppladdning", title);
                return new DocumentInfo(existing.id(), title, existing.chunks(), existing.characters(),
                        existing.path(), existing.collection() != null ? existing.collection() : target,
                        existing.tags() != null && !existing.tags().isEmpty() ? existing.tags() : cleanTags,
                        existing.createdAt(), true);
            }
        }

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
        return index(title, title, target, cleanTags, path, chunks, characters);
    }

    private DocumentInfo index(String title, String source, String collection, List<String> tags, String path,
                               List<IndexedChunk> chunks, int characters) {
        String model = settingsStore.get().embeddingModel();
        if (model == null || model.isBlank()) {
            model = ollama.embeddingModel();
        }
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
        qdrantClient.ensureCollection(collection);

        List<QdrantClient.Point> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            IndexedChunk chunk = chunks.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documentId", documentId);
            payload.put("title", title);
            payload.put("source", source);
            payload.put("collection", collection);
            payload.put("createdAt", createdAt);
            if (!tags.isEmpty()) {
                payload.put("tags", tags);
            }
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

        // Skicka i batcher så att varje request håller sig under Qdrants gräns (32 MB).
        try {
            for (int i = 0; i < points.size(); i += UPSERT_BATCH) {
                List<QdrantClient.Point> batch =
                        new ArrayList<>(points.subList(i, Math.min(i + UPSERT_BATCH, points.size())));
                qdrantClient.upsert(collection, batch);
            }
        } catch (RuntimeException e) {
            // Undvik ett halvindexerat dokument om något batch misslyckas.
            try {
                qdrantClient.deleteByMatch(collection, "documentId", documentId);
            } catch (RuntimeException cleanupError) {
                log.warn("Kunde inte rensa delvis indexerat dokument {}", documentId, cleanupError);
            }
            throw e;
        }

        log.info("Indexerade '{}' ({} textstycken) i kollektionen '{}'", title, chunks.size(), collection);
        return new DocumentInfo(documentId, title, chunks.size(), characters, path, collection, tags,
                Instant.ofEpochMilli(createdAt), false);
    }

    /** Listar dokumenten som faktiskt finns i vektordatabasen (grupperade per dokument). */
    public List<DocumentInfo> list() {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (String collection : qdrantClient.listCollections()) {
            for (Map<String, Object> payload : qdrantClient.scrollPayloads(collection, DOC_FIELDS)) {
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
                Group group = groups.computeIfAbsent(id, key -> new Group(key, docName, collection));
                group.chunks++;
                String path = firstText(payload.get("path"));
                if (path != null) {
                    group.path = path;
                }
                group.tags.addAll(tagsOf(payload));
                Number created = asNumber(payload.get("createdAt"));
                if (created != null && created.longValue() > group.createdAt) {
                    group.createdAt = created.longValue();
                }
            }
        }
        return groups.values().stream()
                .sorted(Comparator.comparingLong((Group group) -> group.createdAt).reversed())
                .map(group -> new DocumentInfo(group.id, group.name, group.chunks, 0, group.path,
                        group.collection, List.copyOf(group.tags),
                        group.createdAt > 0 ? Instant.ofEpochMilli(group.createdAt) : null, false))
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
        for (String collection : qdrantClient.listCollections()) {
            List<Map<String, Object>> payloads = qdrantClient.scrollPayloads(collection,
                    List.of("title", "source", "path", "page", "chunkIndex", "text", "tags"), filter);
            if (!payloads.isEmpty()) {
                return buildDetail(id, collection, payloads);
            }
        }
        throw new IllegalArgumentException("Dokumentet finns inte");
    }

    private DocumentDetail buildDetail(String id, String collection, List<Map<String, Object>> payloads) {
        String name = null;
        String path = null;
        Set<String> tags = new LinkedHashSet<>();
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
            tags.addAll(tagsOf(payload));
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
        return new DocumentDetail(id, name == null ? "Dokument" : name, path, collection,
                List.copyOf(tags), content.size(), characters, content);
    }

    public List<String> collections() {
        return qdrantClient.listCollections();
    }

    /** Alla taggar som används i någon kollektion. */
    public List<String> tags() {
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String collection : qdrantClient.listCollections()) {
            for (Map<String, Object> payload : qdrantClient.scrollPayloads(collection, List.of("tags"))) {
                result.addAll(tagsOf(payload));
            }
        }
        return new ArrayList<>(result);
    }

    public void createCollection(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Kollektionsnamn saknas");
        }
        qdrantClient.ensureCollection(validateName(name.trim()));
    }

    /** Tar bort alla vektorer för ett dokument (matchar documentId eller source) i alla kollektioner. */
    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Inget dokument-id angivet");
        }
        for (String collection : qdrantClient.listCollections()) {
            qdrantClient.deleteByMatch(collection, "documentId", id);
            qdrantClient.deleteByMatch(collection, "source", id);
        }
        log.info("Tog bort dokument {} ur vektordatabasen", id);
    }

    private String resolveCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            return qdrant.collection();
        }
        return validateName(collection.trim());
    }

    private static String validateName(String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Ogiltigt kollektionsnamn: " + name);
        }
        return name;
    }

    private static List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> tagsOf(Map<String, Object> payload) {
        Object value = payload.get("tags");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (Object tag : list) {
            if (tag == null) {
                continue;
            }
            String trimmed = String.valueOf(tag).trim();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    private static final class Group {
        private final String id;
        private final String name;
        private final String collection;
        private String path;
        private int chunks;
        private long createdAt;
        private final Set<String> tags = new LinkedHashSet<>();

        private Group(String id, String name, String collection) {
            this.id = id;
            this.name = name;
            this.collection = collection;
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