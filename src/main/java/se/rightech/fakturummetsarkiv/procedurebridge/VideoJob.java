package se.rightech.fakturummetsarkiv.procedurebridge;

import se.rightech.fakturummetsarkiv.video.ProcedureDraft;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class VideoJob {

    public enum Status { QUEUED, ANALYZING, DONE, FAILED, CANCELLED }

    public final String id;
    public final String filename;
    public volatile Status status = Status.QUEUED;
    public volatile String progressMessage = "Köad";
    public final Instant createdAt = Instant.now();
    public volatile Instant finishedAt;
    public volatile String error;
    public volatile Path savedDir;
    public volatile ProcedureDraft draft;
    public final List<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public VideoJob(String id, String filename) {
        this.id = id;
        this.filename = filename;
    }

    public void send(String name, Object data) {
        List<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> dead = new ArrayList<>();
        for (org.springframework.web.servlet.mvc.method.annotation.SseEmitter e : emitters) {
            try {
                e.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name(name).data(data));
            } catch (Exception ex) {
                dead.add(e);
            }
        }
        emitters.removeAll(dead);
    }
}
