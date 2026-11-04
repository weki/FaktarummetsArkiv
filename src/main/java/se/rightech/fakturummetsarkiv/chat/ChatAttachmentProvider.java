package se.rightech.fakturummetsarkiv.chat;

import java.util.List;
import java.util.Optional;

public interface ChatAttachmentProvider {
    Optional<AttachmentContext> load(AttachmentRef ref);

    record AttachmentContext(String id, String kind, String label, String summaryForGate,
                            String fullText, List<String> frameFiles) {
    }
}
