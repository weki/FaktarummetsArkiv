package se.rightech.fakturummetsarkiv.procedurebridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.chat.AttachmentRef;
import se.rightech.fakturummetsarkiv.chat.ChatAttachmentProvider;
import se.rightech.fakturummetsarkiv.video.VideoAnalysis;
import se.rightech.fakturummetsarkiv.video.VideoEvent;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class ChatAttachmentProviderImpl implements ChatAttachmentProvider {

    private final VideoRunRepository repo;

    public ChatAttachmentProviderImpl(VideoRunRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<AttachmentContext> load(AttachmentRef ref) {
        if (ref == null || !"video".equals(ref.type())) return Optional.empty();
        var analysis = repo.findAnalysis(ref.runId());
        if (analysis == null) return Optional.empty();
        String summary = buildSummary(ref.runId(), analysis);
        String fullText = buildFullText(ref.runId(), analysis);
        List<String> frameFiles = repo.listFrames(ref.runId());
        // seedYaml är nu null - procedur skapas av chattmodellen vid CREATE
        String seedYaml = null;
        return Optional.of(new AttachmentContext(ref.runId(), "video",
                analysis.sourceFilename() == null || analysis.sourceFilename().isBlank() ? ref.runId() : analysis.sourceFilename(),
                summary, fullText, frameFiles, seedYaml));
    }

    private String buildSummary(String runId, VideoAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("file=").append(analysis.sourceFilename()).append(", title=").append(analysis.title());
        sb.append(", events=").append(analysis.events().size());
        if (analysis.summary() != null && !analysis.summary().isBlank()) {
            sb.append(", summary=").append(analysis.summary());
        }
        String s = sb.toString();
        // Begränsa till 300 tecken enligt spec
        if (s.length() > 300) s = s.substring(0, 300);
        return s;
    }

    private String buildFullText(String runId, VideoAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("VIDEO ANALYSIS (run ").append(runId).append(", file \"").append(analysis.sourceFilename()).append("\")\n\n");

        // Summary
        sb.append("## Summary\n");
        sb.append(analysis.summary() != null && !analysis.summary().isBlank() ? analysis.summary() : "No summary available").append("\n\n");

        // Applications
        if (analysis.applications() != null && !analysis.applications().isEmpty()) {
            sb.append("## Applications\n");
            for (String app : analysis.applications()) {
                sb.append("- ").append(app).append("\n");
            }
            sb.append("\n");
        }

        // Timeline
        sb.append("## Timeline\n");
        for (VideoEvent event : analysis.events()) {
            sb.append("[").append(formatTime(event.startSecond())).append("-").append(formatTime(event.endSecond())).append("] ");
            sb.append(event.description());
            if (event.typedText() != null && !event.typedText().isBlank()) {
                sb.append(" | typed=\"").append(event.typedText()).append("\"");
            }
            if (event.onScreenText() != null && !event.onScreenText().isEmpty()) {
                sb.append(" | onScreen=\"").append(String.join("; ", event.onScreenText())).append("\"");
            }
            if (event.application() != null && !event.application().isBlank()) {
                sb.append(" | app=").append(event.application());
            }
            if (event.frameImage() != null) {
                sb.append(" | frame=").append(event.frameImage().getFileName());
            }
            if (event.uncertain()) {
                sb.append(" | UNCERTAIN");
            }
            sb.append("\n");
        }

        // Notable
        if (analysis.notable() != null && !analysis.notable().isEmpty()) {
            sb.append("\n## Notable\n");
            for (String note : analysis.notable()) {
                sb.append("- ").append(note).append("\n");
            }
        }

        // Uncertainties
        if (analysis.uncertainties() != null && !analysis.uncertainties().isEmpty()) {
            sb.append("\n## Uncertainties\n");
            for (String unc : analysis.uncertainties()) {
                sb.append("- ").append(unc).append("\n");
            }
        }

        return sb.toString();
    }

    private static String formatTime(int sec) {
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }
}
