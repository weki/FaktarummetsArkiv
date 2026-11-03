package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Ett procedurutkast utvunnet från en skärminspelning. Sparas inte i vektordatabasen.
 */
public record ProcedureDraft(
        String title,
        String purpose,
        List<String> prerequisites,
        List<ProcedureStep> steps,
        List<String> warnings,
        String sourceFilename,
        String model,
        Path savedDir,
        Map<String, Object> metadata) {
}
