package com.tradecore.admin;

import java.time.Instant;

public record JobRunStatus(String job, Instant startedAt, Instant finishedAt, Long durationMs, String outcome,
        long processed, long updated, long skipped, long failed, String lastError) { }
