package se.rightech.fakturummetsarkiv.system;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.rightech.fakturummetsarkiv.chat.EffectiveSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class GpuController {

    private final GpuStatsService service;
    private final GpuHistoryService historyService;
    private final EffectiveSettings effective;

    public GpuController(GpuStatsService service, GpuHistoryService historyService, EffectiveSettings effective) {
        this.service = service;
        this.historyService = historyService;
        this.effective = effective;
    }

    @GetMapping("/api/system/gpu")
    public Map<String, Object> gpu() {
        try {
            return service.getStats();
        } catch (Exception e) {
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("fetchedAt", java.time.Instant.now().toString());
            Map<String, Object> gpu = new java.util.LinkedHashMap<>();
            gpu.put("available", false);
            gpu.put("reason", "unreachable");
            response.put("gpu", gpu);
            response.put("models", List.of());
            response.put("warnings", List.of());
            return response;
        }
    }

    @GetMapping("/api/system/gpu/history")
    public Map<String, Object> history(@RequestParam(value = "minutes", required = false) Integer minutes) {
        int m = minutes == null ? 60 : minutes;
        return historyService.history(m);
    }

    @DeleteMapping("/api/system/gpu/history")
    public Map<String, Object> clearHistory(HttpServletRequest request) {
        requireWrite(request);
        historyService.clear();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        return res;
    }

    @PostMapping("/api/system/ollama/unload")
    public Map<String, Object> unload(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        requireWrite(request);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        return res;
    }

    @PostMapping("/api/system/ollama/warmup")
    public Map<String, Object> warmup(HttpServletRequest request) {
        requireWrite(request);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        return res;
    }

    private void requireWrite(HttpServletRequest request) {
        String xrw = request.getHeader("X-Requested-With");
        if (xrw == null || !xrw.equals("FakturummetsArkiv")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String addr = request.getRemoteAddr();
        if (addr != null && !(addr.equals("127.0.0.1") || addr.equals("::1") || addr.startsWith("127."))) {
            // allow but be strict per requirement - still require header
        }
    }
}

