package se.rightech.fakturummetsarkiv.video;

import java.util.ArrayList;
import java.util.List;

/**
 * Konverterar mellan ProcedureDraft och VideoAnalysis.
 * Används för bakåtkompatibilitet med gamla körningar och API.
 */
public final class VideoAnalysisCompat {

    private VideoAnalysisCompat() {}

    /**
     * Konverterar en gammal ProcedureDraft till en ny VideoAnalysis.
     */
    public static VideoAnalysis fromDraft(ProcedureDraft draft) {
        if (draft == null) return null;

        List<VideoEvent> events = new ArrayList<>();
        for (ProcedureStep step : draft.steps()) {
            List<String> onScreenText = new ArrayList<>();
            if (step.target() != null && !step.target().isBlank()) {
                onScreenText.add(step.target());
            }
            if (step.expectedResult() != null && !step.expectedResult().isBlank()) {
                onScreenText.add(step.expectedResult());
            }

            events.add(new VideoEvent(
                    step.startSecond(),
                    step.endSecond(),
                    step.instruction(),
                    step.kind() != null ? step.kind().name().toLowerCase() : null,
                    step.inputText(),
                    onScreenText.isEmpty() ? null : onScreenText,
                    null, // application
                    step.startSecond(), // frameSecond (använd startSecond som fallback)
                    step.frameImage(),
                    step.uncertain()
            ));
        }

        String summary = draft.purpose() != null ? draft.purpose() : "";

        return new VideoAnalysis(
                draft.title(),
                summary,
                List.of(), // applications
                events,
                draft.warnings() != null ? draft.warnings() : List.of(),
                List.of(), // uncertainties
                draft.sourceFilename(),
                draft.model(),
                draft.savedDir(),
                draft.metadata()
        );
    }

    /**
     * Konverterar en ny VideoAnalysis till en gammal ProcedureDraft för bakåtkompatibilitet.
     */
    public static ProcedureDraft toDraft(VideoAnalysis analysis) {
        if (analysis == null) return null;

        List<ProcedureStep> steps = new ArrayList<>();
        int stepNum = 1;
        for (VideoEvent event : analysis.events()) {
            // Hoppa över "No visible change" händelser
            if (event.description() != null && event.description().contains("No visible change")) {
                continue;
            }

            String instruction = event.description() != null ? event.description() : "";
            StepKind kind = mapActionToStepKind(event.action());
            String target = null;
            String expectedResult = null;

            if (!event.onScreenText().isEmpty()) {
                target = event.onScreenText().get(0);
                if (event.onScreenText().size() > 1) {
                    expectedResult = String.join(", ", event.onScreenText().subList(1, event.onScreenText().size()));
                }
            }

            steps.add(new ProcedureStep(
                    stepNum++,
                    event.description(),
                    instruction,
                    kind,
                    target,
                    event.typedText(),
                    expectedResult,
                    event.startSecond(),
                    event.endSecond(),
                    event.frameImage(),
                    event.uncertain()
            ));
        }

        return new ProcedureDraft(
                analysis.title(),
                analysis.summary(),
                List.of(), // prerequisites
                steps,
                analysis.notable() != null ? analysis.notable() : List.of(),
                analysis.sourceFilename(),
                analysis.model(),
                analysis.savedDir(),
                analysis.metadata()
        );
    }

    private static StepKind mapActionToStepKind(String action) {
        if (action == null) return StepKind.OTHER;
        return switch (action.toLowerCase()) {
            case "click" -> StepKind.CLICK;
            case "input", "type" -> StepKind.INPUT;
            case "key", "keyboard" -> StepKind.KEY;
            case "navigate", "scroll" -> StepKind.NAVIGATE;
            case "wait", "pause" -> StepKind.WAIT;
            case "verify", "check" -> StepKind.VERIFY;
            default -> StepKind.OTHER;
        };
    }
}
