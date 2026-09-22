package com.procuremind.ai_service.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Output of the screening pass, which reads the section index of a contract in batches and
 * says which sections carry risk.
 *
 * <p>Kept deliberately small. The screening model is asked for a handful of short fields per
 * section rather than a full analysis, because the pass runs once per batch and a large
 * schema is what makes a small model drift away from structured output.
 */
public final class SectionScreeningDtos {

    private SectionScreeningDtos() {
    }

    public record ScreeningResult(
            @JsonPropertyDescription("Sections in this batch that carry risk. Empty array if none of them do.")
            List<SectionFinding> findings) {
    }

    public record SectionFinding(
            @JsonPropertyDescription("The nodeId of the section, copied exactly from the input list")
            String nodeId,

            @JsonPropertyDescription("Risk severity: HIGH, MEDIUM or LOW")
            String severity,

            @JsonPropertyDescription("One short sentence naming the risk found in this section")
            String concern) {
    }
}
