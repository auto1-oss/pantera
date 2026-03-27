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
package com.auto1.pantera.cooldown;

import java.util.Optional;

/**
 * Outcome of a cooldown evaluation.
 */
public final class CooldownResult {

    private final boolean blocked;
    private final CooldownBlock block;

    private CooldownResult(final boolean blocked, final CooldownBlock block) {
        this.blocked = blocked;
        this.block = block;
    }

    public static CooldownResult allowed() {
        return new CooldownResult(false, null);
    }

    public static CooldownResult blocked(final CooldownBlock block) {
        return new CooldownResult(true, block);
    }

    public boolean blocked() {
        return this.blocked;
    }

    public Optional<CooldownBlock> block() {
        return Optional.ofNullable(this.block);
    }
}
