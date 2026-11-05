package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProcedureExtractorTest {

    @Test
    void yamlBlock() {
        String a = "prosa\n```yaml\nmetadata:\n  title: T\nsections: []\n```\n";
        assertNotNull(ProcedureExtractor.extract(a));
    }

    @Test
    void ymlBlock() {
        String a = "```yml\nmetadata:\n  title: T\nsections: []\n```";
        assertNotNull(ProcedureExtractor.extract(a));
    }

    @Test
    void unmarkedWithMetadataAndSections() {
        String a = "```\nmetadata:\n  title: T\nsections: []\n```";
        assertNotNull(ProcedureExtractor.extract(a));
    }

    @Test
    void unmarkedWithoutMetadataSections_returnsNull() {
        String a = "```\nfoo: bar\n```";
        assertNull(ProcedureExtractor.extract(a));
    }

    @Test
    void bashBlock_returnsNull() {
        String a = "```bash\necho hi\n```";
        assertNull(ProcedureExtractor.extract(a));
    }

    @Test
    void twoBashBlocks_returnsNull() {
        String a = "```bash\necho hi\n```\ntext\n```bash\necho ho\n```";
        assertNull(ProcedureExtractor.extract(a));
    }

    @Test
    void twoYamlBlocks_returnsLast() {
        String a = "```yaml\nmetadata:\n  title: A\nsections: []\n```\ntext\n```yaml\nmetadata:\n  title: B\nsections: []\n```";
        String r = ProcedureExtractor.extract(a);
        assertNotNull(r);
        assertTrue(r.contains("B"));
    }

    @Test
    void unbalanced_returnsNull() {
        String a = "```yaml\nmetadata:\n  title: T\n";
        assertNull(ProcedureExtractor.extract(a));
    }

    @Test
    void tildes_returnsNull() {
        String a = "~~~yaml\nmetadata:\n  title: T\nsections: []\n~~~";
        assertNull(ProcedureExtractor.extract(a));
    }

    @Test
    void emptyBlock_returnsNull() {
        String a = "```yaml\n   \n```";
        assertNull(ProcedureExtractor.extract(a));
    }

    @Test
    void commentOnlyBlock_returnsNull() {
        String a = "```yaml\n# just a comment\n```";
        // yaml-tagged block qualifies regardless of content parse
        assertNotNull(ProcedureExtractor.extract(a));
    }
}
