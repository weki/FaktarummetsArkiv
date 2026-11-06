package se.rightech.fakturummetsarkiv.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testar bakåtkompatibilitet: ProcedureDraft ska kunna konverteras till VideoAnalysis.
 */
class VideoAnalysisCompatTest {

    @Test
    void convertsDraftToAnalysis() {
        ProcedureStep step1 = new ProcedureStep(
                1,
                "Open Services",
                "Click the Start button",
                StepKind.CLICK,
                "Start button",
                null,
                "Services window opens",
                5, 10,
                Path.of("frame-005s.jpg"),
                false
        );
        ProcedureStep step2 = new ProcedureStep(
                2,
                "Search for service",
                "Type 'spooler' in search",
                StepKind.INPUT,
                "Search box",
                "spooler",
                "Service appears in list",
                15, 20,
                Path.of("frame-015s.jpg"),
                false
        );

        ProcedureDraft draft = new ProcedureDraft(
                "Restart Print Spooler",
                "Restart the print spooler service",
                List.of("Administrator access"),
                List.of(step1, step2),
                List.of("Service may take a moment to restart"),
                "video.mp4",
                "qwen3-vl:8b",
                Path.of("/tmp/analysis"),
                Map.of("duration", 120)
        );

        VideoAnalysis analysis = VideoAnalysisCompat.fromDraft(draft);

        assertThat(analysis).isNotNull();
        assertThat(analysis.title()).isEqualTo("Restart Print Spooler");
        assertThat(analysis.summary()).isEqualTo("Restart the print spooler service");
        assertThat(analysis.events()).hasSize(2);
        assertThat(analysis.sourceFilename()).isEqualTo("video.mp4");
        assertThat(analysis.model()).isEqualTo("qwen3-vl:8b");

        // Kontrollera första händelsen
        VideoEvent event1 = analysis.events().get(0);
        assertThat(event1.startSecond()).isEqualTo(5);
        assertThat(event1.endSecond()).isEqualTo(10);
        assertThat(event1.description()).isEqualTo("Click the Start button");
        assertThat(event1.action()).isEqualTo("click");
        assertThat(event1.typedText()).isNull();
        assertThat(event1.onScreenText()).containsExactly("Start button", "Services window opens");
        assertThat(event1.frameImage()).isEqualTo(Path.of("frame-005s.jpg"));
        assertThat(event1.uncertain()).isFalse();

        // Kontrollera andra händelsen
        VideoEvent event2 = analysis.events().get(1);
        assertThat(event2.typedText()).isEqualTo("spooler");
        assertThat(event2.action()).isEqualTo("input");

        // Notable ska innehålla warnings
        assertThat(analysis.notable()).contains("Service may take a moment to restart");
    }

    @Test
    void convertsEmptyDraft() {
        ProcedureDraft draft = new ProcedureDraft(
                "Empty Video",
                "Nothing happens",
                List.of(),
                List.of(),
                List.of(),
                "empty.mp4",
                "qwen3-vl:8b",
                Path.of("/tmp/empty"),
                Map.of()
        );

        VideoAnalysis analysis = VideoAnalysisCompat.fromDraft(draft);

        assertThat(analysis).isNotNull();
        assertThat(analysis.title()).isEqualTo("Empty Video");
        assertThat(analysis.events()).isEmpty();
    }

    @Test
    void handlesNullDraft() {
        VideoAnalysis analysis = VideoAnalysisCompat.fromDraft(null);
        assertThat(analysis).isNull();
    }

    @Test
    void preservesUncertainFlag() {
        ProcedureStep uncertainStep = new ProcedureStep(
                1,
                "Uncertain step",
                "Might be clicking something",
                StepKind.OTHER,
                null,
                null,
                null,
                0, 5,
                Path.of("frame-000s.jpg"),
                true
        );

        ProcedureDraft draft = new ProcedureDraft(
                "Test",
                "Test",
                List.of(),
                List.of(uncertainStep),
                List.of(),
                "test.mp4",
                "qwen3-vl:8b",
                Path.of("/tmp/test"),
                Map.of()
        );

        VideoAnalysis analysis = VideoAnalysisCompat.fromDraft(draft);

        assertThat(analysis.events()).hasSize(1);
        assertThat(analysis.events().get(0).uncertain()).isTrue();
    }
}
