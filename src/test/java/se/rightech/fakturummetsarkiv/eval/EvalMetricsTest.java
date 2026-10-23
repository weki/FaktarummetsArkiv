package se.rightech.fakturummetsarkiv.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalMetricsTest {

    private static final List<Map<String, Object>> SOURCES = List.of(
            Map.of("citationNumber", 1, "title", "Honeywell Icon Series"),
            Map.of("citationNumber", 2, "title", "T5810XL Server"));

    @Test
    void computesFactRecallCaseInsensitively() {
        assertThat(EvalMetrics.factRecall("Use the control console [1].", List.of("CONTROL console", "backup")))
                .isEqualTo(0.5);
        assertThat(EvalMetrics.missingFacts("Use the control console [1].", List.of("CONTROL console", "backup")))
                .containsExactly("backup");
    }

    @Test
    void computesCitationPrecisionAndRecall() {
        String answer = "Claim [1] and [2].";

        assertThat(EvalMetrics.citationRecall(answer, SOURCES, List.of("Honeywell Icon Series"))).isEqualTo(1.0);
        assertThat(EvalMetrics.citationPrecision(answer, SOURCES, List.of("Honeywell Icon Series"))).isEqualTo(0.5);
        assertThat(EvalMetrics.missingCitations("Claim [1].", SOURCES, List.of("T5810XL")))
                .containsExactly("T5810XL");
    }

    @Test
    void linksCitationNumbersToTheSourcesActuallySent() {
        String answer = "Claim [9].";

        assertThat(EvalMetrics.citedDocuments(answer, SOURCES)).isEmpty();
        assertThat(EvalMetrics.citationPrecision(answer, SOURCES, List.of("Honeywell"))).isZero();
    }

    @Test
    void measuresNoAnswerAccuracy() {
        assertThat(EvalMetrics.noAnswerCorrect(true, EvalMetrics.NO_ANSWER)).isTrue();
        assertThat(EvalMetrics.noAnswerCorrect(false, EvalMetrics.NO_ANSWER)).isFalse();
        assertThat(EvalMetrics.noAnswerCorrect(false, "Ett riktigt svar.")).isTrue();
    }

    @Test
    void computesBrierScoreWhenValidationExists() {
        assertThat(EvalMetrics.brier(true, true)).isZero();
        assertThat(EvalMetrics.brier(false, false)).isZero();
        assertThat(EvalMetrics.brier(true, false)).isEqualTo(1.0);
        assertThat(EvalMetrics.brier(null, true)).isNaN();
    }
}
