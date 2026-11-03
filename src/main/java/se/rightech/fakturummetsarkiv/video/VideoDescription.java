package se.rightech.fakturummetsarkiv.video;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Resultat av en videoanalys: sammanfattning, segment med tidsangivelser och var
 * delanalyserna sparades på disk.
 */
public record VideoDescription(
        String summary,
        List<VideoSegment> segments,
        Path savedDir,
        Map<String, Object> metadata) {
}
