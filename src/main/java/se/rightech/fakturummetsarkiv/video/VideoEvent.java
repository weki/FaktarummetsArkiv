package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.List;

/**
 * En händelse i en videoanalys.
 */
public record VideoEvent(
        int startSecond,
        int endSecond,
        String description,
        String action,
        String typedText,
        List<String> onScreenText,
        String application,
        int frameSecond,
        Path frameImage,
        boolean uncertain) {
}
