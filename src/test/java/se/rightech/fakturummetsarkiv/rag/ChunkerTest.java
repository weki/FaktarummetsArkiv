package se.rightech.fakturummetsarkiv.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static Chunker chunker(int size, int overlap) {
        return new Chunker(new RagProperties(20, size, overlap, 8000, "en"));
    }

    @Test
    void emptyTextYieldsNoChunks() {
        assertThat(chunker(20, 5).chunk("   ")).isEmpty();
        assertThat(chunker(20, 5).chunk(null)).isEmpty();
    }

    @Test
    void shortTextYieldsSingleChunk() {
        List<Chunker.Chunk> chunks = chunker(100, 10).chunk("  Hej varlden  ");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).text()).isEqualTo("Hej varlden");
    }

    @Test
    void longTextSplitsIntoSequentialChunks() {
        String text = "a".repeat(100);

        List<Chunker.Chunk> chunks = chunker(30, 10).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
            assertThat(chunks.get(i).text().length()).isLessThanOrEqualTo(30);
        }
    }

    @Test
    void overlappingChunksShareText() {
        String text = "abcdefghijklmnopqrstuvwxyz0123456789";

        List<Chunker.Chunk> chunks = chunker(20, 5).chunk(text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        String first = chunks.get(0).text();
        String second = chunks.get(1).text();
        assertThat(first.substring(first.length() - 5)).isEqualTo(second.substring(0, 5));
    }

    @Test
    void splitsAtSentenceBoundary() {
        String text = "Forsta meningen ar har. Andra meningen foljer efter. Tredje meningen avslutar.";

        List<Chunker.Chunk> chunks = chunker(40, 0).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).text()).endsWith(".");
    }
}
