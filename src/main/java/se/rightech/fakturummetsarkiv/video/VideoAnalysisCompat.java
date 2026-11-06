package se.rightech.fakturummetsarkiv.video;

import java.util.ArrayList;
import java.util.List;

/**
 * Konverterar gamla ProcedureDraft till nya VideoAnalysis.
 * Används för bakåtkompatibilitet med gamla körningar.
 */
public final class VideoAnalysisCompat {

    private VideoAnalysisCompat() {}

    /**
     * Konverterar en gammal ProcedureDraft till en VideoAnalysis.
     */
    public static VideoAnalysis fromDraft(ProcedureDraft draft) {
        if (draft == null) return null;

        // Konvertera steg till händelser
        List<VideoEvent> events = new ArrayList<>();
        for (ProcedureStep step : draft.steps()) {
            String description = step.instruction();
            String action = step.kind() != null ? step.kind().name().toLowerCase() : null;
            String typedText = step.inputText();
            List<String> onScreenText = new ArrayList<>();
            if (step.target() != null && !step.target().isBlank()) {
                onScreenText.add(step.target());
            }
            if (step.expectedResult() != null && !step.expectedResult().isBlank()) {
                onScreenText.add(step.expectedResult());
            }

            VideoEvent event = new VideoEvent(
                    step.startSecond(),
                    step.endSecond(),
                    description,
                    action,
                    typedText,
                    onScreenText.isEmpty() ? null : onScreenText,
                    null, // application
                    step.startSecond(), // frameSecond
                    step.frameImage(),
                    step.uncertain()
            );
            events.add(event);
        }

        // Notable: warnings + expectedResult från steg
        List<String> notable = new ArrayList<>(draft.warnings() != null ? draft.warnings() : List.of());

        // Sammanfattning baserad på purpose
        String summary = draft.purpose() != null ? draft.purpose() : "";

        return new VideoAnalysis(
                draft.title(),
                summary,
                List.of(), // applications - inte tillgängligt i gammal draft
                events,
                notable,
                List.of(), // uncertainties
                draft.sourceFilename(),
                draft.model(),
                draft.savedDir(),
                draft.metadata()
        );
    }
}
