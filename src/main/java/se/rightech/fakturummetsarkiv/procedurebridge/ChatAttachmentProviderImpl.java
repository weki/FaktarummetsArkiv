package se.rightech.fakturummetsarkiv.procedurebridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.rightech.fakturummetsarkiv.chat.AttachmentRef;
import se.rightech.fakturummetsarkiv.chat.ChatAttachmentProvider;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "video.enabled", havingValue = "true")
public class ChatAttachmentProviderImpl implements ChatAttachmentProvider {

    private final VideoRunRepository repo;
    private final DocuForgeExporter exporter;
    private final boolean seedFromExport;

    public ChatAttachmentProviderImpl(VideoRunRepository repo, DocuForgeExporter exporter,
                                     @Value("${chat.procedure.seed-from-export:true}") boolean seedFromExport) {
        this.repo = repo;
        this.exporter = exporter;
        this.seedFromExport = seedFromExport;
    }

    @Override
    public Optional<AttachmentContext> load(AttachmentRef ref) {
        if (ref == null || !"video".equals(ref.type())) return Optional.empty();
        var draft = repo.find(ref.runId());
        if (draft == null) return Optional.empty();
        String summary = buildSummary(ref.runId(), draft);
        String fullText = buildFullText(ref.runId(), draft);
        List<String> frameFiles = repo.listFrames(ref.runId());
        String seedYaml = null;
        if (seedFromExport && exporter != null) {
            try { seedYaml = exporter.toYaml(draft); } catch (Exception e) { seedYaml = null; }
        }
        return Optional.of(new AttachmentContext(ref.runId(), "video",
                draft.sourceFilename() == null || draft.sourceFilename().isBlank() ? ref.runId() : draft.sourceFilename(),
                summary, fullText, frameFiles, seedYaml));
    }

    private String buildSummary(String runId, se.rightech.fakturummetsarkiv.video.ProcedureDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("file=").append(draft.sourceFilename()).append(", title=").append(draft.title())
                .append(", steps=").append(draft.steps().size());
        if (draft.steps().size() <= 12) {
            sb.append(", titles: ");
            for (int i = 0; i < draft.steps().size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(draft.steps().get(i).title());
            }
        }
        String s = sb.toString();
        if (s.length() > 800) s = s.substring(0, 800);
        return s;
    }

    private String buildFullText(String runId, se.rightech.fakturummetsarkiv.video.ProcedureDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("VIDEO ANALYSIS (run ").append(runId).append(", file \"").append(draft.sourceFilename()).append("\")\\n");
        sb.append("Title: ").append(draft.title()).append("\\n");
        sb.append("Purpose: ").append(draft.purpose()).append("\\n");
        if (draft.prerequisites() != null && !draft.prerequisites().isEmpty()) {
            sb.append("Prerequisites: ").append(String.join("; ", draft.prerequisites())).append("\\n");
        }
        if (draft.warnings() != null && !draft.warnings().isEmpty()) {
            sb.append("Warnings: ").append(String.join("; ", draft.warnings())).append("\\n");
        }
        sb.append("Steps:\\n");
        for (se.rightech.fakturummetsarkiv.video.ProcedureStep s : draft.steps()) {
            sb.append(s.number()).append(". [").append(formatTime(s.startSecond())).append("-").append(formatTime(s.endSecond())).append("] ")
                    .append(s.title()).append(" | kind=").append(s.kind())
                    .append(" | target=").append(s.target())
                    .append(" | input=\"").append(s.inputText() == null ? "" : s.inputText()).append("\"")
                    .append(" | expected=").append(s.expectedResult())
                    .append(" | frame=").append(s.frameImage() == null ? "" : s.frameImage().getFileName())
                    .append(s.uncertain() ? " | UNCERTAIN" : "").append("\\n");
        }
        return sb.toString();
    }

    private static String formatTime(int sec) {
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }
}
