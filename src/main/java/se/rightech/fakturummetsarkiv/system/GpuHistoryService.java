package se.rightech.fakturummetsarkiv.system;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GpuHistoryService {

    private static final Logger log = LoggerFactory.getLogger(GpuHistoryService.class);

    private final GpuStatsService gpuStatsService;
    private final EffectiveSettings effective;

    private final Deque<Map<String, Object>> raw = new ArrayDeque<>();
    private final Object lock = new Object();

    public GpuHistoryService(GpuStatsService gpuStatsService, EffectiveSettings effective) {
        this.gpuStatsService = gpuStatsService;
        this.effective = effective;
    }

    private Path historyFile() {
        try {
            String dir = System.getProperty("rag.data-dir");
            if (dir == null || dir.isBlank()) {
                dir = System.getenv("RAG_DATA_DIR");
            }
            if (dir == null || dir.isBlank()) {
                dir = "data";
            }
            Path p = Paths.get(dir);
            Files.createDirectories(p);
            return p.resolve("gpu-history.jsonl");
        } catch (Exception e) {
            return Paths.get("gpu-history.jsonl");
        }
    }

    @PostConstruct
    public void load() {
        synchronized (lock) {
            try {
                Path f = historyFile();
                if (!Files.exists(f)) {
                    return;
                }
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    try {
                        Map<String, Object> m = tools.jackson.databind.json.JsonMapper.builder().build()
                                .readValue(line, Map.class);
                        raw.addLast(m);
                    } catch (Exception ex) {
                        log.warn("Kunde inte läsa rad i GPU-historik");
                    }
                }
                prune();
                enforceSize();
            } catch (Exception e) {
                log.warn("Kunde inte läsa GPU-historik", e);
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void sample() {
        try {
            boolean enabled = effective.bool("gpu.history-enabled");
            if (!enabled) {
                return;
            }
            String statsUrl = effective.string("gpu.stats-url");
            if (statsUrl == null || statsUrl.isBlank()) {
                return;
            }
            Map<String, Object> stats = gpuStatsService.getStats();
            if (stats == null) {
                return;
            }
            Map<String, Object> gpu = stats.get("gpu") instanceof Map<?, ?> g ? (Map<String, Object>) g : null;
            if (gpu == null || !Boolean.TRUE.equals(gpu.get("available"))) {
                // record gap marker
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("t", Instant.now().toString());
                gap.put("gap", true);
                synchronized (lock) {
                    raw.addLast(gap);
                    prune();
                    enforceSize();
                }
                maybeSave();
                return;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", Instant.now().toString());
            if (gpu.get("utilPct") instanceof Number n1) {
                point.put("util", n1.intValue());
            }
            if (gpu.get("memUsedMiB") instanceof Number n2) {
                point.put("mem", n2.intValue());
            }
            if (gpu.get("tempC") instanceof Number n3) {
                point.put("temp", n3.intValue());
            }
            if (gpu.get("powerW") instanceof Number n4) {
                point.put("power", n4.doubleValue());
            }
            Object modelsObj = stats.get("models");
            if (modelsObj instanceof List<?> ml) {
                List<Map<String, Object>> mm = new ArrayList<>();
                for (Object o : ml) {
                    if (o instanceof Map<?, ?> m2) {
                        Map<String, Object> em = new LinkedHashMap<>();
                        em.put("name", m2.get("name"));
                        if (m2.get("gpuPct") instanceof Number gn) {
                            em.put("gpuPct", gn.intValue());
                        }
                        mm.add(em);
                    }
                }
                if (!mm.isEmpty()) {
                    point.put("models", mm);
                }
            }
            synchronized (lock) {
                raw.addLast(point);
                prune();
                enforceSize();
            }
            maybeSave();
        } catch (Exception e) {
            log.debug("GPU-historikprov misslyckades", e);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void maybeSave() {
        save();
    }

    @PreDestroy
    public void onShutdown() {
        save();
    }

    private void save() {
        synchronized (lock) {
            try {
                Path f = historyFile();
                Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
                Path parent = f.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
                    for (Map<String, Object> m : raw) {
                        w.write(mapper.writeValueAsString(m));
                        w.newLine();
                    }
                    w.flush();
                }
                if (!Files.exists(tmp)) {
                    throw new IOException("Temporärfilen skapades inte: " + tmp);
                }
                try {
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException | NoSuchFileException moveException) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warn("Kunde inte spara GPU-historik", e);
            }
        }
    }

    private void prune() {
        try {
            int hours = effective.integer("gpu.history-hours");
            if (hours < 1) hours = 24;
            Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
            while (!raw.isEmpty()) {
                Map<String, Object> first = raw.peekFirst();
                String ts = first == null ? null : String.valueOf(first.get("t"));
                if (ts == null) {
                    raw.pollFirst();
                    continue;
                }
                try {
                    Instant it = Instant.parse(ts);
                    if (it.isBefore(cutoff)) {
                        raw.pollFirst();
                    } else {
                        break;
                    }
                } catch (Exception ex) {
                    raw.pollFirst();
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void enforceSize() {
        try {
            Path f = historyFile();
            if (Files.exists(f)) {
                long size = Files.size(f);
                if (size > 5 * 1024 * 1024) {
                    while (!raw.isEmpty() && (Files.exists(f) ? Files.size(f) : 0) > 5 * 1024 * 1024) {
                        raw.pollFirst();
                    }
                }
            }
            // also trim in memory if very large
            while (raw.size() > 20000) {
                raw.pollFirst();
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public Map<String, Object> history(int minutes) {
        int m = minutes;
        if (m < 1) m = 1;
        int maxMin = effective.integer("gpu.history-hours") * 60;
        if (maxMin < 60) maxMin = 1440;
        if (m > maxMin) m = maxMin;
        List<Map<String, Object>> points = new ArrayList<>();
        Instant cutoff = Instant.now().minus(m, ChronoUnit.MINUTES);
        synchronized (lock) {
            prune();
            if (m <= 120) {
                for (Map<String, Object> p : raw) {
                    String ts = p == null ? null : String.valueOf(p.get("t"));
                    if (ts == null) continue;
                    try {
                        Instant it = Instant.parse(ts);
                        if (it.isBefore(cutoff)) continue;
                    } catch (Exception ignore) {
                    }
                    points.add(p);
                }
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("resolution", "raw");
                res.put("points", points.size() > 2000 ? downsample(points, 2000) : points);
                return res;
            } else {
                // minute averages
                Map<Long, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
                for (Map<String, Object> p : raw) {
                    if (p == null) continue;
                    if (Boolean.TRUE.equals(p.get("gap"))) {
                        String ts = String.valueOf(p.get("t"));
                        try {
                            Instant it = Instant.parse(ts);
                            long key = it.truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
                            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                        } catch (Exception ignore) {
                        }
                        continue;
                    }
                    String ts = String.valueOf(p.get("t"));
                    try {
                        Instant it = Instant.parse(ts);
                        if (it.isBefore(cutoff)) continue;
                        long key = it.truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
                        buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                    } catch (Exception ignore) {
                    }
                }
                List<Long> keys = new ArrayList<>(buckets.keySet());
                keys.sort(Long::compareTo);
                for (Long k : keys) {
                    List<Map<String, Object>> bucket = buckets.get(k);
                    boolean hasGap = false;
                    int su = 0, sc = 0;
                    int sm = 0, smc = 0;
                    int st = 0, stc = 0;
                    double sp = 0, spc = 0;
                    int mu = -1, Mu = -1;
                    int mmv = -1, Mmv = -1;
                    int mtv = -1, Mtv = -1;
                    double mpv = -1, Mpv = -1;
                    for (Map<String, Object> p : bucket) {
                        if (Boolean.TRUE.equals(p.get("gap"))) {
                            hasGap = true;
                            continue;
                        }
                        if (p.get("util") instanceof Number nu) {
                            int v = nu.intValue();
                            su += v;
                            sc++;
                            if (mu == -1 || v < mu) mu = v;
                            if (Mu == -1 || v > Mu) Mu = v;
                        }
                        if (p.get("mem") instanceof Number nm) {
                            int v = nm.intValue();
                            sm += v;
                            smc++;
                            if (mmv == -1 || v < mmv) mmv = v;
                            if (Mmv == -1 || v > Mmv) Mmv = v;
                        }
                        if (p.get("temp") instanceof Number nt) {
                            int v = nt.intValue();
                            st += v;
                            stc++;
                            if (mtv == -1 || v < mtv) mtv = v;
                            if (Mtv == -1 || v > Mtv) Mtv = v;
                        }
                        if (p.get("power") instanceof Number np) {
                            double v = np.doubleValue();
                            sp += v;
                            spc++;
                            if (mpv == -1 || v < mpv) mpv = v;
                            if (Mpv == -1 || v > Mpv) Mpv = v;
                        }
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("t", Instant.ofEpochMilli(k).toString());
                    if (hasGap && sc == 0 && smc == 0) {
                        out.put("gap", true);
                        points.add(out);
                        continue;
                    }
                    if (sc > 0) {
                        out.put("util", su / sc);
                        out.put("maxUtil", Mu);
                    }
                    if (smc > 0) {
                        out.put("mem", sm / smc);
                        out.put("maxMem", Mmv);
                    }
                    if (stc > 0) {
                        out.put("temp", st / stc);
                    }
                    if (spc > 0) {
                        out.put("power", sp / spc);
                    }
                    points.add(out);
                }
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("resolution", "minute");
                res.put("points", points.size() > 2000 ? downsample(points, 2000) : points);
                return res;
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            raw.clear();
            try {
                Files.deleteIfExists(historyFile());
            } catch (Exception ignore) {
            }
        }
    }

    private List<Map<String, Object>> downsample(List<Map<String, Object>> in, int max) {
        if (in.size() <= max) return new ArrayList<>(in);
        List<Map<String, Object>> out = new ArrayList<>(max);
        double step = (double) in.size() / (double) max;
        for (int i = 0; i < max; i++) {
            int idx = (int) Math.floor(i * step);
            out.add(in.get(idx));
        }
        return out;
    }
}
