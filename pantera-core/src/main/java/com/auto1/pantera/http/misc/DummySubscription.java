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
package com.auto1.pantera.http.misc;

import org.reactivestreams.Subscription;

/**
 * Dummy subscription that do nothing.
 * It's a requirement of reactive-streams specification to
 * call {@code onSubscribe} on subscriber before any other call.
 */
public enum DummySubscription implements Subscription {
    /**
     * Dummy value.
     */
    VALUE;

    @Override
    public void request(final long amount) {
        // does nothing
    }

    @Override
    public void cancel() {
        // does nothing
    }
}
