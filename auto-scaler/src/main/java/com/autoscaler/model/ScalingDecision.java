package com.autoscaler.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ScalingDecision {

    public enum Action   { SCALE_UP, SCALE_DOWN, RESTART, NONE }
    public enum Severity { LOW, MEDIUM, HIGH }

    private Action       action;
    private String       reason;
    private Severity     severity;

    /** Ordered remediation steps produced by the AI fix-plan prompt. */
    private List<String> fixPlan;

    /** Human-readable summary sentence. */
    private String       summary;
}