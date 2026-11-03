package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Publikt API för videoanalys. Implementeras av {@code video.internal}.
 */
public interface VideoDescriber {
    VideoDescription describe(Path videoFile, String originalFilename, VideoOptions options,
                             Consumer<String> progress);
}
