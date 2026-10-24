package se.rightech.fakturummetsarkiv.chat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final SettingsAccessGuard accessGuard;

    public SettingsController(SettingsService settingsService, SettingsAccessGuard accessGuard) {
        this.settingsService = settingsService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public SettingsService.SettingsSnapshot get() {
        return settingsService.snapshot();
    }

    @PostMapping
    public SettingsService.SettingsSnapshot update(@RequestBody SettingsUpdateRequest request,
                                                   HttpServletRequest httpRequest) {
        accessGuard.requireWriteAccess(httpRequest);
        if (request == null || request.values() == null) {
            throw new SettingsValidationException(null, "Inga inställningar angivna");
        }
        return settingsService.update(request.values(), request.expectedVersion());
    }

    @DeleteMapping("/{key}")
    public SettingsService.SettingsSnapshot reset(@PathVariable String key,
                                                  @RequestBody(required = false) VersionRequest request,
                                                  HttpServletRequest httpRequest) {
        accessGuard.requireWriteAccess(httpRequest);
        return settingsService.reset(key, request == null ? null : request.expectedVersion());
    }

    @PostMapping("/reset")
    public SettingsService.SettingsSnapshot resetAll(@RequestBody(required = false) VersionRequest request,
                                                      HttpServletRequest httpRequest) {
        accessGuard.requireWriteAccess(httpRequest);
        return settingsService.resetAll(request == null ? null : request.expectedVersion());
    }

    @ExceptionHandler(ReadOnlySettingException.class)
    public ResponseEntity<Map<String, Object>> readOnly(ReadOnlySettingException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", exception.getMessage(), "field", exception.field()));
    }

    @ExceptionHandler(SettingsAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(SettingsAccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(SettingsValidationException.class)
    public ResponseEntity<Map<String, Object>> invalid(SettingsValidationException exception) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", exception.getMessage());
        if (exception.field() != null) {
            body.put("field", exception.field());
        }
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(StaleSettingsException.class)
    public ResponseEntity<Map<String, Object>> stale(StaleSettingsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    public record SettingsUpdateRequest(Map<String, Object> values, Long expectedVersion) {
    }

    public record VersionRequest(Long expectedVersion) {
    }
}
