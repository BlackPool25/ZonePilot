package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

public class SkipStopRequest {

    @NotBlank
    private String reason;

    /** Client idempotency key: redelivered offline-queue skips dedupe to 409. */
    private String opId;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOpId() { return opId; }
    public void setOpId(String opId) { this.opId = opId; }
}
