package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface VideoDescriber {
    /**
     * @deprecated Använd {@link #describe(Path, String, VideoOptions, Consumer)} istället.
     */
    @Deprecated
    ProcedureDraft draft(Path videoFile, String originalFilename, VideoOptions options,
                        Consumer<String> progress);

    /**
     * Analyserar en video och returnerar en allmän beskrivning av vad som händer.
     */
    VideoAnalysis describe(Path videoFile, String originalFilename, VideoOptions options,
                          Consumer<String> progress);
}
