package se.rightech.fakturummetsarkiv.video.internal;

import java.util.List;
import java.util.Map;

public record OllamaChatRequest(
        String model,
        boolean stream,
        List<Map<String, Object>> messages,
        Map<String, Object> options,
        String keep_alive,
        boolean think
) {
}
