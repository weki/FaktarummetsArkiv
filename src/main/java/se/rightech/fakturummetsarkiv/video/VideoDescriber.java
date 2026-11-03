package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface VideoDescriber {
    ProcedureDraft draft(Path videoFile, String originalFilename, VideoOptions options,
                        Consumer<String> progress);
}
