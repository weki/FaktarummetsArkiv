package se.rightech.fakturummetsarkiv.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;
import se.rightech.fakturummetsarkiv.chat.SettingsStore;
import se.rightech.fakturummetsarkiv.llm.OllamaProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class GpuStatsService {

    private static final Logger log = LoggerFactory.getLogger(GpuStatsService.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final EffectiveSettings effective;
    private final SettingsStore settingsStore;
    private final OllamaProperties ollama;
    private final RestClient restClient;

    private volatile Map<String, Object> cached;
    private final AtomicLong lastFetch = new AtomicLong(0);
    private final Object lock = new Object();
    private CompletableFuture<Map<String, Object>> inFlight;

    private volatile boolean gpuWasOk = false;
    private volatile boolean ollamaWasOk = false;

    public GpuStatsService(EffectiveSettings effective, SettingsStore settingsStore, OllamaProperties ollama) {
        this.effective = effective;
        this.settingsStore = settingsStore;
        this.ollama = ollama;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory())
                .build();
    }

    private org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(1500));
        factory.setReadTimeout(Duration.ofMillis(2000));
        return factory;
    }

    public Map<String, Object> getStats() {
        long now = System.currentTimeMillis();
        if (cached != null && now - lastFetch.get() < 1500) {
            return cached;
        }
        synchronized (lock) {
            if (cached != null && System.currentTimeMillis() - lastFetch.get() < 1500) {
                return cached;
            }
            if (inFlight != null && !inFlight.isDone()) {
                try {
                    return inFlight.get();
                } catch (Exception e) {
                    // fall through to new fetch
                }
            }
            inFlight = CompletableFuture.supplyAsync(this::fetch);
            try {
                Map<String, Object> result = inFlight.get();
                return result;
            } catch (Exception e) {
                Map<String, Object> fallback = cached == null ? emptyResponse("unreachable") : cached;
                return fallback;
            }
        }
    }

    private Map<String, Object> fetch() {
        String statsUrl = getStatsUrl();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fetchedAt", Instant.now().toString());

        Map<String, Object> gpu = new LinkedHashMap<>();
        if (statsUrl == null || statsUrl.isBlank()) {
            gpu.put("available", false);
            gpu.put("reason", "disabled");
            if (!gpuWasOk) {
                // no log on first disabled
            }
            gpuWasOk = false;
        } else if (!isAllowed(statsUrl)) {
            gpu.put("available", false);
            gpu.put("reason", "unreachable");
            logOnce("gpu", false);
            gpuWasOk = false;
        } else {
            try {
                JsonNode node = restClient.get().uri(statsUrl).retrieve().body(JsonNode.class);
                if (node == null) {
                    gpu.put("available", false);
                    gpu.put("reason", "unreachable");
                    logOnce("gpu", false);
                    gpuWasOk = false;
                } else if (node.has("available") && node.get("available").isBoolean() && !node.get("available").asBoolean()) {
                    gpu.put("available", false);
                    gpu.put("reason", "unavailable");
                    logOnce("gpu", false);
                    gpuWasOk = false;
                } else {
                    parseGpu(node, gpu);
                    if (Boolean.TRUE.equals(gpu.get("available"))) {
                        logOnce("gpu", true);
                        gpuWasOk = true;
                    } else {
                        logOnce("gpu", false);
                        gpuWasOk = false;
                    }
                }
            } catch (Exception ex) {
                gpu.put("available", false);
                gpu.put("reason", "unreachable");
                logOnce("gpu", false);
                gpuWasOk = false;
            }
        }
        response.put("gpu", gpu);

        List<Map<String, Object>> models = fetchOllamaModels();
        response.put("models", models);

        List<Map<String, Object>> warnings = new ArrayList<>();
        computeWarnings(gpu, models, warnings);
        response.put("warnings", warnings);

        cached = response;
        lastFetch.set(System.currentTimeMillis());
        return response;
    }

    private String getStatsUrl() {
        try {
            String v = effective.string("gpu.stats-url");
            if (v == null || v.isBlank()) {
                try {
                    se.rightech.fakturummetsarkiv.chat.WorkspaceSettings ws = settingsStore.get();
                    // try to access via effective overrides? or just use effective
                } catch (Exception ignore) {
                }
            }
            if (v == null || v.isBlank()) {
                // also check effective directly
                try {
                    v = effective.string("gpu.stats-url");
                } catch (Exception ignore) {
                }
            }
            if (v == null || v.isBlank()) {
                return null;
            }
            v = v.trim();
            if (!(v.startsWith("http://") || v.startsWith("https://"))) {
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAllowed(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if (!scheme.equals("http") || !scheme.equals("https")) {
                // allow both
            }
            return scheme.equals("http") || scheme.equals("https");
        } catch (Exception e) {
            return false;
        }
    }

    private void parseGpu(JsonNode node, Map<String, Object> gpu) {
        gpu.put("available", true);
        try {
            if (node.has("gpus") && node.get("gpus").isArray() && node.get("gpus").size() > 0) {
                JsonNode g = node.get("gpus").get(0);
                if (g.has("name")) {
                    gpu.put("name", g.get("name").asText());
                }
                gpu.put("utilPct", toInt(g.get("utilization.gpu")));
                gpu.put("memUsedMiB", toInt(g.get("memory.used")));
                gpu.put("memTotalMiB", toInt(g.get("memory.total")));
                gpu.put("tempC", toInt(g.get("temperature.gpu")));
                gpu.put("powerW", toDouble(g.get("power.draw")));
                gpu.put("powerLimitW", toDouble(g.get("power.limit")));
            } else if (node.has("gpu")) {
                JsonNode g = node.get("gpu");
                if (g.has("name")) {
                    gpu.put("name", g.get("name").asText());
                }
                gpu.put("utilPct", toInt(g.get("utilization.gpu") != null ? g.get("utilization.gpu") : g.get("utilPct")));
                gpu.put("memUsedMiB", toInt(g.get("memory.used") != null ? g.get("memory.used") : g.get("memUsedMiB")));
                gpu.put("memTotalMiB", toInt(g.get("memory.total") != null ? g.get("memory.total") : g.get("memTotalMiB")));
                gpu.put("tempC", toInt(g.get("temperature.gpu") != null ? g.get("temperature.gpu") : g.get("tempC")));
                gpu.put("powerW", toDouble(g.get("power.draw") != null ? g.get("power.draw") : g.get("powerW")));
                gpu.put("powerLimitW", toDouble(g.get("power.limit") != null ? g.get("power.limit") : g.get("powerLimitW")));
            }
            List<Map<String, Object>> processes = new ArrayList<>();
            if (node.has("processes") && node.get("processes").isArray()) {
                for (JsonNode p : node.get("processes")) {
                    if (p.isArray() && p.size() >= 3) {
                        Map<String, Object> proc = new LinkedHashMap<>();
                        proc.put("pid", toInt(p.get(0)));
                        proc.put("name", p.get(1).asText(""));
                        proc.put("memMiB", toInt(p.get(2)));
                        processes.add(proc);
                    } else if (p.isObject()) {
                        Map<String, Object> proc = new LinkedHashMap<>();
                        proc.put("pid", toInt(p.get("pid")));
                        proc.put("name", p.has("name") ? p.get("name").asText("") : "");
                        proc.put("memMiB", toInt(p.has("used_mib") ? p.get("used_mib") : (p.has("memMiB") ? p.get("memMiB") : null)));
                        processes.add(proc);
                    }
                }
            }
            gpu.put("processes", processes);
        } catch (Exception e) {
            // keep available true if structure unexpected but available true
        }
    }

    private Integer toInt(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("[N/A]") || s.equalsIgnoreCase("N/A")) {
            return null;
        }
        // remove % etc
        s = s.replace("%", "").replace(",", ".");
        try {
            // try int first
            if (s.contains(".")) {
                double d = Double.parseDouble(s);
                return (int) Math.round(d);
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Double toDouble(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("[N/A]") || s.equalsIgnoreCase("N/A")) {
            return null;
        }
        s = s.replace("%", "").replace(",", ".");
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> fetchOllamaModels() {
        List<Map<String, Object>> models = new ArrayList<>();
        try {
            String base = ollama.baseUrl();
            if (base == null) {
                base = "http://localhost:11434";
            }
            JsonNode node = restClient.get().uri(base + "/api/ps").retrieve().body(JsonNode.class);
            if (node == null || !node.has("models") || !node.get("models").isArray()) {
                if (!ollamaWasOk) {
                    // log once
                }
                logOnce("ollama", node != null);
                ollamaWasOk = node != null;
                if (node == null || !node.has("models")) {
                    // add warning OLLAMA_UNREACHABLE
                }
                return models;
            }
            logOnce("ollama", true);
            ollamaWasOk = true;
            for (JsonNode m : node.get("models")) {
                Map<String, Object> model = new LinkedHashMap<>();
                if (m.has("name")) {
                    model.put("name", m.get("name").asText());
                }
                if (m.has("size")) {
                    model.put("sizeBytes", m.get("size").asLong());
                }
                if (m.has("size_vram")) {
                    model.put("sizeVramBytes", m.get("size_vram").asLong());
                } else if (m.has("sizeVRAM")) {
                    model.put("sizeVramBytes", m.get("sizeVRAM").asLong());
                }
                Object size = model.get("sizeBytes");
                Object sizeVram = model.get("sizeVramBytes");
                if (size instanceof Number sn && sizeVram instanceof Number svn && sn.longValue() > 0) {
                    double pct = 100.0 * svn.doubleValue() / sn.doubleValue();
                    model.put("gpuPct", (int) Math.round(pct));
                }
                if (m.has("context_length")) {
                    model.put("contextLength", m.get("context_length").asInt());
                } else if (m.has("contextLength")) {
                    model.put("contextLength", m.get("contextLength").asInt());
                }
                if (m.has("expires_at")) {
                    model.put("expiresAt", m.get("expires_at").asText());
                }
                models.add(model);
            }
            return models;
        } catch (Exception e) {
            logOnce("ollama", false);
            ollamaWasOk = false;
            return models;
        }
    }

    private void computeWarnings(Map<String, Object> gpu, List<Map<String, Object>> models, List<Map<String, Object>> warnings) {
        try {
            // PARTIAL_CPU
            for (Map<String, Object> m : models) {
                Object size = m.get("sizeBytes");
                Object sizeVram = m.get("sizeVramBytes");
                if (size instanceof Number sn && sizeVram instanceof Number svn) {
                    if (svn.longValue() < sn.longValue()) {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("code", "PARTIAL_CPU");
                        w.put("model", m.get("name"));
                        w.put("message", "Modellen kör delvis på CPU (endast en del i VRAM).");
                        warnings.add(w);
                        Object gpct = m.get("gpuPct");
                        if (gpct == null) {
                            double pct = 100.0 * svn.doubleValue() / sn.doubleValue();
                            m.put("gpuPct", (int) Math.round(pct));
                        }
                    }
                }
            }
            // VRAM_HIGH
            Object mu = gpu.get("memUsedMiB");
            Object mt = gpu.get("memTotalMiB");
            if (mu instanceof Number mun && mt instanceof Number mtn && mtn.doubleValue() > 0) {
                if (mun.doubleValue() / mtn.doubleValue() > 0.95) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("code", "VRAM_HIGH");
                    w.put("message", "VRAM-användning över 95 %.");
                    warnings.add(w);
                }
            }
            // CTX_MISMATCH: samma modell konfigurerad med olika num_ctx för olika roller.
            Map<String, List<String>> rolesByModel = new LinkedHashMap<>();
            Map<String, Integer> ctxByRole = new LinkedHashMap<>();
            for (String role : List.of("chat", "validator", "refine", "gate", "rewrite", "followup", "rerank")) {
                String model = effective.effectiveModel(role);
                if (model == null || model.isBlank()) {
                    continue;
                }
                rolesByModel.computeIfAbsent(model, key -> new ArrayList<>()).add(role);
                ctxByRole.put(role, effective.effectiveNumCtx(role));
            }
            for (Map.Entry<String, List<String>> entry : rolesByModel.entrySet()) {
                List<Integer> values = new ArrayList<>();
                for (String role : entry.getValue()) {
                    Integer ctx = ctxByRole.get(role);
                    if (ctx != null && !values.contains(ctx)) {
                        values.add(ctx);
                    }
                }
                if (values.size() > 1) {
                    Map<String, Object> warning = new LinkedHashMap<>();
                    warning.put("code", "CTX_MISMATCH");
                    warning.put("model", entry.getKey());
                    warning.put("message", "Modellen " + entry.getKey() + " används med olika num_ctx ("
                            + values + "), modellen laddas om mellan anropen.");
                    warnings.add(warning);
                }
            }
        } catch (Exception ignore) {
        }
    }

    private final ConcurrentHashMap<String, Boolean> lastState = new ConcurrentHashMap<>();

    private void logOnce(String key, boolean ok) {
        Boolean last = lastState.get(key);
        if (last == null) {
            lastState.put(key, ok);
            return;
        }
        if (last.booleanValue() != ok) {
            if (ok) {
                log.info("{} återansluten", key);
            } else {
                log.warn("{} anslutning bruten", key);
            }
            lastState.put(key, ok);
        }
    }

    private Map<String, Object> emptyResponse(String reason) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fetchedAt", Instant.now().toString());
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("available", false);
        if (reason != null) {
            gpu.put("reason", reason);
        }
        response.put("gpu", gpu);
        response.put("models", new ArrayList<>());
        response.put("warnings", new ArrayList<>());
        return response;
    }
}
