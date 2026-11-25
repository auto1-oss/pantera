/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.StorageThrottledException;
import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * Failure of an S3 call, told apart into "slow down" and everything else.
 *
 * <p>Throttling is a 503 or 429 answer, or one of the throttling error
 * codes; AWS answers {@code SlowDown}, MinIO {@code SlowDownRead} and
 * {@code SlowDownWrite}. The same codes appear as per-key errors inside a
 * multi-object delete answer.</p>
 *
 * @since 2.2.14
 */
final class S3Failure {

    /**
     * Error codes that mean throttling.
     */
    private static final Set<String> CODES = Set.of(
        "RequestLimitExceeded", "Throttling", "ThrottlingException",
        "RequestThrottled", "TooManyRequests", "ServiceUnavailable"
    );

    /**
     * Code prefix of the S3 "slow down" family.
     */
    private static final String SLOW = "SlowDown";

    /**
     * Failure.
     */
    private final Throwable failure;

    /**
     * Ctor.
     * @param failure Failure of the call
     */
    S3Failure(final Throwable failure) {
        this.failure = failure;
    }

    /**
     * Whether the store asked to slow down.
     * @return True for a throttling answer
     */
    boolean throttled() {
        boolean res = false;
        Throwable cur = this.failure;
        int depth = 0;
        while (cur != null && !res && depth < 16) {
            if (cur instanceof SdkServiceException svc) {
                res = svc.statusCode() == 503 || svc.statusCode() == 429
                    || svc.isThrottlingException();
            }
            if (!res && cur instanceof AwsServiceException aws
                && aws.awsErrorDetails() != null) {
                res = this.throttling(aws.awsErrorDetails().errorCode());
            }
            cur = cur.getCause();
            depth += 1;
        }
        return res;
    }

    /**
     * Whether an S3 error code means throttling.
     * @param code Error code, may be null
     * @return True if it does
     */
    boolean throttling(final String code) {
        return code != null && (code.startsWith(S3Failure.SLOW) || S3Failure.CODES.contains(code));
    }

    /**
     * The failure as a storage exception.
     * @param what What was attempted, for the message
     * @return {@link StorageThrottledException} for a throttling answer,
     *  {@link PanteraIOException} otherwise
     */
    RuntimeException translated(final String what) {
        final RuntimeException res;
        if (this.throttled()) {
            res = new StorageThrottledException(
                String.format("S3 throttled %s: %s", what, this.root().getMessage()),
                this.failure
            );
        } else {
            res = new PanteraIOException(
                String.format("S3 failed %s: %s", what, this.root().getMessage()),
                this.failure
            );
        }
        return res;
    }

    /**
     * The failure without its {@code CompletionException} wrappers.
     * @return Cause
     */
    private Throwable root() {
        Throwable cur = this.failure;
        while (cur instanceof java.util.concurrent.CompletionException
            && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }
}
