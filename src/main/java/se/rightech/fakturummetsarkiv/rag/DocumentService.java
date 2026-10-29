package se.rightech.fakturummetsarkiv.rag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int EMBED_BATCH = 16;
    /** Antal punkter per upsert-anrop mot Qdrant (håller varje request långt under 32 MB). */
    private static final int UPSERT_BATCH = 64;
    private static final List<String> DOC_FIELDS =
            List.of("documentId", "source", "title", "path", "page", "createdAt", "collection", "tags",
                    "contentHash", "characters", "text");

    private final OllamaClient llm;
    private final OllamaProperties ollama;
    private final QdrantProperties qdrant;
    private final QdrantClient qdrantClient;
    private final Chunker chunker;
    private final PdfTextExtractor pdfTextExtractor;
    private final FileStore fileStore;
    private final SettingsStore settingsStore;
    private final ConcurrentHashMap<String, Object> hashLocks = new ConcurrentHashMap<>();

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

    public record DocumentInfo(String id, String name, int chunks, int characters, @JsonIgnore String path,
                               String collection, List<String> tags, Instant createdAt, boolean skipped,
                               String notice) {
    }

    public record DocumentChunk(Integer page, Integer chunkIndex, String text) {
    }

    public record DocumentDetail(String id, String name, @JsonIgnore String path, String collection, List<String> tags,
                                 int chunks, int characters, List<DocumentChunk> content) {
    }

    private record IndexedChunk(Integer page, int index, String text) {
    }

    /** Filuppladdning: indexerar innehållet och sparar originalet först när det lyckats. */
    public DocumentInfo addFile(String filename, byte[] bytes, String collection, List<String> tags)
            throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Filen är tom");
        }
        String title = filename == null || filename.isBlank() ? "Dokument" : filename.trim();
        // Behåll bara filnamnet, inte sökvägen (viktigt vid mappuppladdning)
        int slash = Math.max(title.lastIndexOf('/'), title.lastIndexOf('\\'));
        if (slash >= 0) {
            title = title.substring(slash + 1);
        }
        if (title.isBlank()) {
            title = "Dokument";
        }
        String target = resolveCollection(collection);
        List<String> cleanTags = cleanTags(tags);

        validateContent(title, bytes);
        String contentHash = sha256(bytes);

        Object lock = hashLocks.computeIfAbsent(contentHash, key -> new Object());
        synchronized (lock) {
            DocumentInfo existing = findByContentHash(contentHash);
            if (existing != null) {
                log.info("Identiskt innehåll {} finns redan som dokument {} – hoppar över uppladdning",
                        contentHash, existing.id());
                return new DocumentInfo(existing.id(), existing.name(), existing.chunks(), existing.characters(),
                        existing.path(), existing.collection(), existing.tags(), existing.createdAt(), true,
                        "Identiskt innehåll finns redan som " + existing.name() + " i " + existing.collection()
                                + ". Nya taggar och kollektion har inte tillämpats.");
            }

            List<IndexedChunk> chunks;
            int characters;
            if (isPdf(title)) {
                chunks = new ArrayList<>();
                characters = 0;
                StringBuilder whole = new StringBuilder();
                List<PageStart> pageStarts = new ArrayList<>();
                for (PdfTextExtractor.PdfPage page : pdfTextExtractor.extract(bytes)) {
                    if (whole.length() > 0) {
                        whole.append('\n');
                    }
                    pageStarts.add(new PageStart(whole.length(), page.page()));
                    whole.append(page.text());
                    characters += page.text().length();
                }
                int index = 0;
                for (Chunker.Chunk chunk : chunker.chunk(whole.toString())) {
                    int midpoint = chunk.start() + chunk.text().length() / 2;
                    chunks.add(new IndexedChunk(pageAt(pageStarts, midpoint), index++, chunk.text()));
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

            Path stored = fileStore.store(title, bytes);
            String path = stored.toAbsolutePath().toString();
            try {
                return index(title, title, target, cleanTags, path, chunks, characters, contentHash);
            } catch (RuntimeException | IOException e) {
                fileStore.delete(stored);
                throw e;
            }
        }
    }

    /** Söker efter ett identiskt innehåll utan att läsa in alla punkter i kollektionerna. */
    private DocumentInfo findByContentHash(String contentHash) {
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "contentHash", "match", Map.of("value", contentHash))));
        for (String collection : List.of(qdrant.collection())) {
            List<Map<String, Object>> payloads =
                    qdrantClient.scrollPayloads(collection, DOC_FIELDS, filter);
            if (!payloads.isEmpty()) {
                return infoFromPayloads(collection, payloads);
            }
        }
        return null;
    }

    private static DocumentInfo infoFromPayloads(String collection, List<Map<String, Object>> payloads) {
        Map<String, Object> first = payloads.get(0);
        String id = firstText(first.get("documentId"));
        String name = firstText(first.get("title"));
        String path = firstText(first.get("path"));
        Set<String> tags = new LinkedHashSet<>();
        long characters = 0;
        long createdAt = 0;
        for (Map<String, Object> payload : payloads) {
            tags.addAll(tagsOf(payload));
            Number chars = asNumber(payload.get("characters"));
            if (chars != null) {
                characters = Math.max(characters, chars.longValue());
            } else {
                String text = firstText(payload.get("text"));
                if (text != null) {
                    characters += text.length();
                }
            }
            Number created = asNumber(payload.get("createdAt"));
            if (created != null) {
                createdAt = Math.max(createdAt, created.longValue());
            }
        }
        return new DocumentInfo(id, name == null ? "Dokument" : name, payloads.size(), (int) characters, path,
                collection, List.copyOf(tags), createdAt > 0 ? Instant.ofEpochMilli(createdAt) : null,
                true, null);
    }

    private DocumentInfo index(String title, String source, String collection, List<String> tags, String path,
                               List<IndexedChunk> chunks, int characters, String contentHash)
            throws IOException {
        String model = settingsStore.get().embeddingModel();
        if (model == null || model.isBlank()) {
            model = ollama.embeddingModel();
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Ingen embedding-modell konfigurerad (ollama.embedding-model)");
        }
        String embeddingModel = model;

        String qdrantCollection = qdrant.collection();
        qdrantClient.ensureCollection(qdrantCollection);
        requireCompatibleCollection(qdrantCollection, embeddingModel);

        List<double[]> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            List<String> inputs = chunks.subList(i, Math.min(i + EMBED_BATCH, chunks.size())).stream()
                    .map(chunk -> qdrant.passageInput(embeddingModel, embeddingText(title, chunk)))
                    .toList();
            List<double[]> batch = llm.embed(embeddingModel, inputs);
            if (batch.size() != inputs.size()) {
                throw new IllegalStateException("Kunde inte skapa embeddings för alla textstycken");
            }
            for (double[] vector : batch) {
                if (vector.length != qdrant.vectorSize()) {
                    throw new IllegalStateException("Embeddingmodellen " + embeddingModel + " ger "
                            + vector.length + " dimensioner men kollektionen '" + collection + "' använder "
                            + qdrant.vectorSize());
                }
            }
            vectors.addAll(batch);
        }

        String documentId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();

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
            payload.put("embeddingModel", embeddingModel);
            payload.put("vectorSize", qdrant.vectorSize());
            payload.put("contentHash", contentHash);
            payload.put("characters", characters);
            points.add(new QdrantClient.Point(UUID.randomUUID().toString(), vectors.get(i), payload));
        }

        // Skicka i batcher så att varje request håller sig under Qdrants gräns (32 MB).
        try {
            for (int i = 0; i < points.size(); i += UPSERT_BATCH) {
                List<QdrantClient.Point> batch =
                        new ArrayList<>(points.subList(i, Math.min(i + UPSERT_BATCH, points.size())));
                qdrantClient.upsert(qdrantCollection, batch);
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

        log.info("Indexerade dokument {} ({} textstycken) i kollektionen '{}'", documentId, chunks.size(), qdrantCollection);
        return new DocumentInfo(documentId, title, chunks.size(), characters, path, collection, tags,
                Instant.ofEpochMilli(createdAt), false, null);
    }

    private void requireCompatibleCollection(String collection, String embeddingModel) {
        Map<String, Object> metadata = qdrantClient.firstEmbeddingMetadata(collection);
        Object storedModel = metadata.get("embeddingModel");
        if (storedModel != null && !String.valueOf(storedModel).isBlank()
                && !String.valueOf(storedModel).equals(embeddingModel)) {
            throw new IllegalStateException("Kollektionen '" + collection + "' använder embeddingmodellen "
                    + storedModel + " och kan inte blandas med " + embeddingModel);
        }
        Object storedSize = metadata.get("vectorSize");
        if (storedSize instanceof Number number && number.intValue() != qdrant.vectorSize()) {
            throw new IllegalStateException("Kollektionen '" + collection + "' använder "
                    + number.intValue() + " dimensioner och kan inte blandas med " + qdrant.vectorSize());
        }
    }

    private static String embeddingText(String title, IndexedChunk chunk) {
        StringBuilder sb = new StringBuilder("Document: ").append(title).append('\n');
        if (chunk.page() != null) {
            sb.append("Page: ").append(chunk.page()).append('\n');
        }
        return sb.append('\n').append(chunk.text()).toString();
    }

    private static Integer pageAt(List<PageStart> starts, int offset) {
        Integer page = null;
        for (PageStart start : starts) {
            if (start.offset() > offset) {
                break;
            }
            page = start.page();
        }
        return page;
    }

    private record PageStart(int offset, int page) {
    }

    /** Listar dokumenten som faktiskt finns i vektordatabasen (grupperade per dokument). */
    public List<DocumentInfo> list() {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (String collection : List.of(qdrant.collection())) {
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
                Number chars = asNumber(payload.get("characters"));
                if (chars != null) {
                    group.characters = Math.max(group.characters, chars.longValue());
                } else {
                    String text = firstText(payload.get("text"));
                    if (text != null) {
                        group.characters += text.length();
                    }
                }
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
                .map(group -> new DocumentInfo(group.id, group.name, group.chunks, (int) group.characters,
                        group.path, group.collection, List.copyOf(group.tags),
                        group.createdAt > 0 ? Instant.ofEpochMilli(group.createdAt) : null, false, null))
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
        List<Map<String, Object>> allPayloads = new ArrayList<>();
        Set<String> collections = new LinkedHashSet<>();
        for (String collection : List.of(qdrant.collection())) {
            List<Map<String, Object>> payloads = qdrantClient.scrollPayloads(collection,
                    List.of("title", "source", "path", "page", "chunkIndex", "text", "tags", "collection"), filter);
            if (!payloads.isEmpty()) {
                allPayloads.addAll(payloads);
                collections.add(collection);
            }
        }
        if (allPayloads.isEmpty()) {
            throw new IllegalArgumentException("Dokumentet finns inte");
        }
        return buildDetail(id, String.join(", ", collections), allPayloads);
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
        String main = qdrant.collection();
        Set<String> result = new LinkedHashSet<>();
        try {
            for (Map<String, Object> payload : qdrantClient.scrollPayloads(main, List.of("collection"))) {
                String value = firstText(payload.get("collection"));
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        if (result.isEmpty()) {
            result.add(main);
        }
        return new ArrayList<>(result);
    }

    /** Uppdaterar taggar för ett dokument (alla dess chunks). */
    public void updateTags(String documentId, List<String> tags) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Inget dokument-id angivet");
        }
        List<String> clean = cleanTags(tags);
        qdrantClient.updateTags(qdrant.collection(), documentId, clean);
    }

    /** Alla taggar som används i någon kollektion. */
    public List<String> tags() {
        TreeSet<String> result = new TreeSet<>();
        for (String collection : List.of(qdrant.collection())) {
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

    /** Tar bort alla vektorer för ett dokument via documentId och raderar originalet. */
    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Inget dokument-id angivet");
        }
        Set<String> paths = new LinkedHashSet<>();
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "documentId", "match", Map.of("value", id))));
        for (String collection : List.of(qdrant.collection())) {
            for (Map<String, Object> payload : qdrantClient.scrollPayloads(collection,
                    List.of("path"), filter)) {
                String path = firstText(payload.get("path"));
                if (path != null) {
                    paths.add(path);
                }
            }
            qdrantClient.deleteByMatch(collection, "documentId", id);
        }
        for (String path : paths) {
            fileStore.delete(Path.of(path));
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
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = normalizeTag(tag);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> tagsOf(Map<String, Object> payload) {
        Object value = payload.get("tags");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (Object tag : list) {
            String normalized = normalizeTag(tag == null ? null : String.valueOf(tag));
            if (normalized != null) {
                tags.add(normalized);
            }
        }
        return List.copyOf(tags);
    }

    private static String normalizeTag(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 är inte tillgängligt", e);
        }
    }

    private static final class Group {
        private final String id;
        private final String name;
        private final String collection;
        private String path;
        private int chunks;
        private long characters;
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
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static final List<String> TEXT_EXTENSIONS =
            List.of(".txt", ".md", ".csv", ".json", ".log", ".xml");

    private static void validateContent(String filename, byte[] bytes) {
        if (isPdf(filename)) {
            if (!looksLikePdf(bytes)) {
                throw new IllegalArgumentException("Filen har .pdf-ändelse men innehållet är inte en PDF");
            }
            return;
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (TEXT_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new IllegalArgumentException("Filtypen stöds inte. Tillåtna: PDF, TXT, MD, CSV, JSON, LOG, XML");
        }
        if (looksBinary(bytes)) {
            throw new IllegalArgumentException("Filen verkar vara binär och kan inte läsas som text");
        }
    }

    private static boolean looksLikePdf(byte[] bytes) {
        byte[] magic = {'%', 'P', 'D', 'F', '-'};
        int limit = Math.min(bytes.length - magic.length, 1024);
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < magic.length; j++) {
                if (bytes[i + j] != magic[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}