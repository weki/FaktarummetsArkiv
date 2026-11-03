package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;

/**
 * Ett steg i ett procedurutkast.
 */
public record ProcedureStep(
        int number,
        String title,
        String instruction,
        StepKind kind,
        String target,
        String inputText,
        String expectedResult,
        int startSecond,
        int endSecond,
        Path frameImage,
        boolean uncertain) {
}
