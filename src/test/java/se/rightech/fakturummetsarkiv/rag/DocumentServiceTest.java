package se.rightech.fakturummetsarkiv.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import se.rightech.fakturummetsarkiv.llm.OllamaClient;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceTest {

    @TempDir
    Path uploadDir;

    @Mock
    OllamaClient llm;

    @Mock
    QdrantClient qdrantClient;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        OllamaProperties ollama = new OllamaProperties(
                "http://localhost:11434", "chat", "embed-model", Duration.ofSeconds(30),
                "validator", false, "", "", "", 16384, 4096);
        QdrantProperties qdrant = new QdrantProperties(
                "http://localhost:6333", "docs", 1024, "Cosine", 5,
                Duration.ofSeconds(10), "query: ", "passage: ");
        RagProperties rag = new RagProperties(20, 40, 10, 8000, "en", 0.3);
        Chunker chunker = new Chunker(rag);
        ArchiveProperties archive = new ArchiveProperties("", uploadDir.toString());

        service = new DocumentService(llm, ollama, qdrant, qdrantClient, chunker,
                new PdfTextExtractor(), new FileStore(archive),
                new se.rightech.fakturummetsarkiv.chat.SettingsStore(qdrant, ollama, rag, new se.rightech.fakturummetsarkiv.chat.ChatProperties(0.1, true)));

        when(llm.embed(anyString(), anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(1);
            List<double[]> vectors = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                vectors.add(new double[1024]);
            }
            return vectors;
        });
    }

    @Test
    void indexesPdfAndStoresOriginalFile() throws IOException {
        DocumentService.DocumentInfo info = service.addFile("manual.pdf", twoPagePdf(), "docs", List.of("manual"));

        assertThat(info.name()).isEqualTo("manual.pdf");
        assertThat(info.chunks()).isPositive();
        assertThat(info.collection()).isEqualTo("docs");
        assertThat(info.tags()).containsExactly("manual");

        List<Map<String, Object>> payloads = capturedPayloads();
        assertThat(payloads).isNotEmpty();
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload.get("title")).isEqualTo("manual.pdf");
            assertThat(payload.get("collection")).isEqualTo("docs");
            assertThat(payload.get("tags")).isEqualTo(List.of("manual"));
            assertThat(String.valueOf(payload.get("documentId"))).isNotBlank();
            assertThat(payload.get("text").toString()).isNotBlank();
            assertThat(String.valueOf(payload.get("contentHash"))).matches("[0-9a-f]{64}");
            assertThat(payload.get("characters")).isInstanceOf(Number.class);
        });
        assertThat(payloads).anySatisfy(payload -> assertThat(payload.get("page")).isEqualTo(1));
        assertThat(payloads).anySatisfy(payload -> assertThat(payload.get("page")).isEqualTo(2));
        assertThat(payloads).allSatisfy(payload -> {
            String path = payload.get("path").toString();
            assertThat(Files.exists(Path.of(path))).isTrue();
            assertThat(path).startsWith(uploadDir.toAbsolutePath().toString());
        });

        verify(qdrantClient).ensureCollection("docs");
    }

    @Test
    void embedsTitleAndPageHeaderButKeepsPayloadTextClean() throws IOException {
        service.addFile("manual.pdf", twoPagePdf(), "docs", List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> inputs = ArgumentCaptor.forClass(List.class);
        verify(llm, org.mockito.Mockito.atLeastOnce()).embed(eq("embed-model"), inputs.capture());

        List<String> allInputs = inputs.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(allInputs).anySatisfy(input ->
                assertThat(input).startsWith("Document: manual.pdf\nPage: 1\n\n"));
        assertThat(allInputs).anySatisfy(input ->
                assertThat(input).contains("Page: 2"));
        assertThat(capturedPayloads()).allSatisfy(payload ->
                assertThat(String.valueOf(payload.get("text"))).doesNotContain("Document:"));
        assertThat(capturedPayloads()).allSatisfy(payload -> {
            assertThat(payload.get("embeddingModel")).isEqualTo("embed-model");
            assertThat(payload.get("vectorSize")).isEqualTo(1024);
        });
    }

    @Test
    void refusesToMixEmbeddingModelsInOneCollection() {
        when(qdrantClient.firstEmbeddingMetadata("docs")).thenReturn(
                Map.of("embeddingModel", "other-model", "vectorSize", 1024));

        assertThatThrownBy(() -> service.addFile("manual.pdf", twoPagePdf(), "docs", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("other-model");
        verify(qdrantClient, org.mockito.Mockito.never()).upsert(anyString(), anyList());
    }

    @Test
    void refusesToMixVectorSizesInOneCollection() {
        when(qdrantClient.firstEmbeddingMetadata("docs")).thenReturn(
                Map.of("embeddingModel", "embed-model", "vectorSize", 768));

        assertThatThrownBy(() -> service.addFile("manual.pdf", twoPagePdf(), "docs", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("768");
    }

    @Test
    void uploadsToNamedCollection() throws IOException {
        service.addFile("manual.pdf", twoPagePdf(), "manuals", List.of());

        verify(qdrantClient).ensureCollection("manuals");
        verify(qdrantClient).upsert(eq("manuals"), any());
    }

    @Test
    void defaultsToPrimaryCollectionWhenBlank() throws IOException {
        DocumentService.DocumentInfo info = service.addFile("manual.pdf", twoPagePdf(), "  ", List.of());

        assertThat(info.collection()).isEqualTo("docs");
        verify(qdrantClient).ensureCollection("docs");
    }

    @Test
    void rejectsInvalidCollectionName() {
        assertThatThrownBy(() -> service.addFile("manual.pdf", new byte[]{1}, "bad/name", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexesPlainTextFileWithoutPageInfo() throws IOException {
        DocumentService.DocumentInfo info = service.addFile("noteringar.txt",
                "Det här är en kort notering om fakturor.".getBytes(), "docs", List.of());

        assertThat(info.name()).isEqualTo("noteringar.txt");
        assertThat(capturedPayloads()).allSatisfy(payload -> {
            assertThat(payload).containsKey("path");
            assertThat(payload).doesNotContainKey("page");
        });
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> service.addFile("tom.pdf", new byte[0], "docs", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsDocumentsGroupedAcrossCollections() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs", "manuals"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList())).thenReturn(List.of(
                Map.of("documentId", "d1", "title", "a.pdf", "collection", "docs", "createdAt", 1000L),
                Map.of("documentId", "d1", "title", "a.pdf", "collection", "docs", "createdAt", 1000L),
                Map.of("source", "gammal.pdf", "path", "/x/gammal.pdf", "text", "gammal text")));
        when(qdrantClient.scrollPayloads(eq("manuals"), anyList())).thenReturn(List.of(
                Map.of("documentId", "d2", "title", "b.pdf", "collection", "manuals",
                        "tags", List.of("manual"), "createdAt", 2000L)));

        List<DocumentService.DocumentInfo> docs = service.list();

        assertThat(docs).hasSize(3);
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.id()).isEqualTo("d1");
            assertThat(doc.name()).isEqualTo("a.pdf");
            assertThat(doc.chunks()).isEqualTo(2);
        });
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.id()).isEqualTo("gammal.pdf");
            assertThat(doc.characters()).isEqualTo("gammal text".length());
        });
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.id()).isEqualTo("gammal.pdf");
            assertThat(doc.chunks()).isEqualTo(1);
        });
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.id()).isEqualTo("d2");
            assertThat(doc.collection()).isEqualTo("manuals");
            assertThat(doc.tags()).containsExactly("manual");
        });
    }

    @Test
    void listsTagsAcrossCollections() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs", "manuals"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList())).thenReturn(List.of(
                Map.of("tags", List.of("manual", "experion"))));
        when(qdrantClient.scrollPayloads(eq("manuals"), anyList())).thenReturn(List.of(
                Map.of("tags", List.of("experion", "faktura"))));

        assertThat(service.tags()).containsExactlyInAnyOrder("manual", "experion", "faktura");
    }

    @Test
    void createsCollectionWithValidatedName() {
        service.createCollection("manuals");

        verify(qdrantClient).ensureCollection("manuals");
        assertThatThrownBy(() -> service.createCollection(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesByDocumentIdOnlyInEveryCollection() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs", "manuals"));

        service.delete("d1");

        verify(qdrantClient).deleteByMatch("docs", "documentId", "d1");
        verify(qdrantClient).deleteByMatch("manuals", "documentId", "d1");
        verify(qdrantClient, org.mockito.Mockito.never()).deleteByMatch(anyString(), eq("source"), anyString());
    }

    @Test
    void rejectsDeleteWithoutId() {
        assertThatThrownBy(() -> service.delete(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsDocumentDetailWithContentSortedByPage() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList(), any())).thenReturn(List.of(
                Map.of("title", "a.pdf", "path", "/x/a.pdf", "tags", List.of("manual"),
                        "page", 2, "chunkIndex", 0, "text", "Sida två"),
                Map.of("title", "a.pdf", "path", "/x/a.pdf", "page", 1, "chunkIndex", 0, "text", "Sida ett")));

        DocumentService.DocumentDetail detail = service.detail("d1");

        assertThat(detail.name()).isEqualTo("a.pdf");
        assertThat(detail.path()).isEqualTo("/x/a.pdf");
        assertThat(detail.collection()).isEqualTo("docs");
        assertThat(detail.tags()).containsExactly("manual");
        assertThat(detail.chunks()).isEqualTo(2);
        assertThat(detail.content()).extracting(DocumentService.DocumentChunk::page).containsExactly(1, 2);
        assertThat(detail.content().get(0).text()).isEqualTo("Sida ett");
        assertThat(detail.characters()).isEqualTo("Sida ett".length() + "Sida två".length());
    }

    @Test
    void detailRejectsUnknownDocument() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.detail("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedPayloads() {
        ArgumentCaptor<List<QdrantClient.Point>> captor = ArgumentCaptor.forClass(List.class);
        verify(qdrantClient).upsert(anyString(), captor.capture());
        return captor.getValue().stream()
                .map(QdrantClient.Point::payload)
                .toList();
    }

    @Test
    void doesNotSerializeServerPaths() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String info = json.writeValueAsString(new DocumentService.DocumentInfo(
                "id", "a.pdf", 1, 10, "/srv/hemligt/a.pdf", "docs", List.of(), Instant.now(), false, null));
        String detail = json.writeValueAsString(new DocumentService.DocumentDetail(
                "id", "a.pdf", "/srv/hemligt/a.pdf", "docs", List.of(), 1, 10, List.of()));

        assertThat(info).doesNotContain("/srv/hemligt");
        assertThat(detail).doesNotContain("/srv/hemligt");
    }

    private static byte[] twoPagePdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage();
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    stream.newLineAtOffset(72, 700);
                    stream.showText("Sida " + page + " manuellt skriven text.");
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void skipsIdenticalContentAndExplainsWhyTagsWereNotApplied() throws IOException {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList(), any())).thenReturn(List.of(
                Map.of("documentId", "old", "title", "original.txt", "collection", "docs",
                        "contentHash", "abc", "characters", 5, "tags", List.of("old"))));

        DocumentService.DocumentInfo info = service.addFile(
                "kopia.txt", "samma".getBytes(), "manuals", List.of("ny"));

        assertThat(info.skipped()).isTrue();
        assertThat(info.id()).isEqualTo("old");
        assertThat(info.notice()).contains("Nya taggar och kollektion har inte tillämpats");
        verify(qdrantClient, never()).upsert(anyString(), anyList());
        try (var files = Files.list(uploadDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void deletesStoredOriginalWhenIndexingFails() throws IOException {
        when(llm.embed(anyString(), anyList())).thenThrow(new IllegalStateException("Ollama nere"));

        assertThatThrownBy(() -> service.addFile(
                "trasig.txt", "innehall".getBytes(), "docs", List.of()))
                .isInstanceOf(IllegalStateException.class);
        try (var files = Files.list(uploadDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void deletesOriginalFileWhenDocumentIsDeleted() throws IOException {
        Path original = Files.writeString(uploadDir.resolve("original.txt"), "innehall");
        when(qdrantClient.listCollections()).thenReturn(List.of("docs"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList(), any())).thenReturn(List.of(
                Map.of("documentId", "d1", "path", original.toString())));

        service.delete("d1");

        assertThat(Files.exists(original)).isFalse();
        verify(qdrantClient).deleteByMatch("docs", "documentId", "d1");
    }

    @Test
    void normalisesTagsToLowerCaseAndDeduplicates() throws IOException {
        DocumentService.DocumentInfo info = service.addFile(
                "taggar.txt", "innehall".getBytes(), "docs", List.of("Manual", "MANUAL", " Experion "));

        assertThat(info.tags()).containsExactly("manual", "experion");
        assertThat(capturedPayloads().get(0).get("tags")).isEqualTo(List.of("manual", "experion"));
    }

    @Test
    void detailMergesMatchesFromSeveralCollections() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs", "manuals"));
        when(qdrantClient.scrollPayloads(eq("docs"), anyList(), any())).thenReturn(List.of(
                Map.of("title", "a.pdf", "chunkIndex", 0, "text", "del ett")));
        when(qdrantClient.scrollPayloads(eq("manuals"), anyList(), any())).thenReturn(List.of(
                Map.of("title", "a.pdf", "chunkIndex", 1, "text", "del tva")));

        DocumentService.DocumentDetail detail = service.detail("d1");

        assertThat(detail.chunks()).isEqualTo(2);
        assertThat(detail.collection()).isEqualTo("docs, manuals");
        assertThat(detail.content()).extracting(DocumentService.DocumentChunk::text)
                .containsExactly("del ett", "del tva");
    }
}
