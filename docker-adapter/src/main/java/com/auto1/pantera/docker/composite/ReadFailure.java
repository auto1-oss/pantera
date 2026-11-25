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
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.http.UpstreamStatusException;
import com.auto1.pantera.http.log.FailureCause;

/**
 * Which of the failures of several read sources to report when none of them
 * had the object.
 *
 * <p>A source that answered a 4xx other than 429 refused the request (a
 * registry answers 401 or 403 for an image it does not host): that reads as
 * "not here". A source that could not answer at all (5xx, 429, a failed
 * call) may hold the object, so its failure is the graver one and is the one
 * reported.</p>
 *
 * @since 2.2.14
 */
final class ReadFailure {

    /**
     * Utility.
     */
    private ReadFailure() {
    }

    /**
     * The graver of two failures.
     * @param one Failure, or null
     * @param two Failure, or null
     * @return The one to report, null when both are null
     */
    static Throwable graver(final Throwable one, final Throwable two) {
        final Throwable res;
        if (one == null) {
            res = two;
        } else if (two == null) {
            res = one;
        } else {
            res = ReadFailure.refusal(one) && !ReadFailure.refusal(two) ? two : one;
        }
        return res;
    }

    /**
     * Whether a failure is the upstream refusing the request.
     * @param err Failure
     * @return True for an upstream 4xx other than 429
     */
    private static boolean refusal(final Throwable err) {
        return new FailureCause(err).root() instanceof UpstreamStatusException status
            && status.status() >= 400 && status.status() < 500 && status.status() != 429;
    }
}
