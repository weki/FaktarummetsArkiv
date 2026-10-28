package se.rightech.fakturummetsarkiv.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class GpuController {

    private final GpuStatsService service;

    public GpuController(GpuStatsService service) {
        this.service = service;
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
            response.put("models", java.util.List.of());
            response.put("warnings", java.util.List.of());
            return response;
        }
    }
}
