/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
