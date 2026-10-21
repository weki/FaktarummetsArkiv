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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                "validator", false, "", "", "");
        QdrantProperties qdrant = new QdrantProperties(
                "http://localhost:6333", "docs", 1024, "Cosine", 5,
                Duration.ofSeconds(10), "query: ", "passage:");
        RagProperties rag = new RagProperties(20, 1200, 200, 8000);
        Chunker chunker = new Chunker(rag);
        ArchiveProperties archive = new ArchiveProperties("", uploadDir.toString());

        service = new DocumentService(llm, ollama, qdrant, qdrantClient, chunker,
                new PdfTextExtractor(), new FileStore(archive),
                new se.rightech.fakturummetsarkiv.chat.SettingsStore(qdrant, ollama, rag));

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
                Map.of("source", "gammal.pdf", "path", "/x/gammal.pdf")));
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
    void deletesByDocumentIdAndSourceInEveryCollection() {
        when(qdrantClient.listCollections()).thenReturn(List.of("docs", "manuals"));

        service.delete("d1");

        verify(qdrantClient).deleteByMatch("docs", "documentId", "d1");
        verify(qdrantClient).deleteByMatch("docs", "source", "d1");
        verify(qdrantClient).deleteByMatch("manuals", "documentId", "d1");
        verify(qdrantClient).deleteByMatch("manuals", "source", "d1");
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
}
