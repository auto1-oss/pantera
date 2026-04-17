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
package com.auto1.pantera.cooldown.api;

/**
 * Reasons for triggering a cooldown block.
 */
public enum CooldownReason {
    /**
     * Requested version is newer than the currently cached version.
     */
    NEWER_THAN_CACHE,
    /**
     * Requested version was released recently and has never been cached before.
     */
    FRESH_RELEASE
}
