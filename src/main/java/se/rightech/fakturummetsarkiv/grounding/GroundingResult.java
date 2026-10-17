package se.rightech.fakturummetsarkiv.grounding;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroundingResult(Boolean supported, String reason, Boolean refused) {

    public GroundingResult(Boolean supported, String reason) {
        this(supported, reason, null);
    }
}
