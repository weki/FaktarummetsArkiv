package se.rightech.fakturummetsarkiv.grounding;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroundingResult(Boolean supported, String reason, Boolean refused,
                              String verdict, List<String> unsupportedClaims) {

    public static final String SUPPORTED = "SUPPORTED";
    public static final String PARTIALLY_SUPPORTED = "PARTIALLY_SUPPORTED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String NOT_ENOUGH_INFO = "NOT_ENOUGH_INFO";

    public GroundingResult(Boolean supported, String reason) {
        this(supported, reason, null, null, null);
    }

    public GroundingResult(Boolean supported, String reason, Boolean refused) {
        this(supported, reason, refused, null, null);
    }

    public GroundingResult(Boolean supported, String reason, String verdict, List<String> unsupportedClaims) {
        this(supported, reason, null, verdict, unsupportedClaims);
    }

    public static String verdictFor(Boolean supported) {
        if (supported == null) {
            return null;
        }
        return supported ? SUPPORTED : UNSUPPORTED;
    }

    public static Boolean supportedFor(String verdict) {
        if (verdict == null) {
            return null;
        }
        return SUPPORTED.equals(verdict) ? Boolean.TRUE : Boolean.FALSE;
    }

    public static String normalizeVerdict(String verdict) {
        if (verdict == null) {
            return null;
        }
        return switch (verdict.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "SUPPORTED" -> SUPPORTED;
            case "PARTIALLY_SUPPORTED", "PARTIAL" -> PARTIALLY_SUPPORTED;
            case "UNSUPPORTED" -> UNSUPPORTED;
            case "NOT_ENOUGH_INFO", "INSUFFICIENT_INFORMATION", "INSUFFICIENT" -> NOT_ENOUGH_INFO;
            default -> null;
        };
    }
}
