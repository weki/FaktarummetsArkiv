package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * En allmän beskrivning av vad som händer i en video.
 * Ersätter ProcedureDraft som var procedurfokuserad.
 */
public record VideoAnalysis(
        String title,
        String summary,
        List<String> applications,
        List<VideoEvent> events,
        List<String> notable,
        List<String> uncertainties,
        String sourceFilename,
        String model,
        Path savedDir,
        Map<String, Object> metadata) {
}
