/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.perms;

import com.auto1.pantera.security.perms.Action;

import java.util.Collections;
import java.util.Set;

/**
 * Docker actions.
 */
public enum DockerActions implements Action {

    PULL(0x4, "pull"),
    PUSH(0x2, "push"),
    OVERWRITE(0x10, "overwrite"),

    /**
     * Manifest/blob deletion (GC, {@code skopeo delete}). Deliberately its own
     * bit rather than folded into {@link #PUSH} — a role granted ordinary
     * push/pull must not silently gain delete; it has to be requested
     * explicitly (per-resource or via the {@code *} wildcard, which resolves
     * to {@link #ALL}).
     */
    DELETE(0x20, "delete"),
    ALL(0x4 | 0x2 | 0x10 | 0x20, "*");

    /**
     * Action mask.
     */
    private final int mask;

    /**
     * Action name.
     */
    private final String name;

    /**
     * @param mask Action mask
     * @param name Action name
     */
    DockerActions(int mask, String name) {
        this.mask = mask;
        this.name = name;
    }

    @Override
    public Set<String> names() {
        return Collections.singleton(this.name);
    }

    @Override
    public int mask() {
        return this.mask;
    }

    /**
     * Get action int mask by name.
     * @param name The action name
     * @return The mask
     * @throws IllegalArgumentException is the action not valid
     */
    public static int maskByAction(String name) {
        for (Action item : values()) {
            if (item.names().contains(name)) {
                return item.mask();
            }
        }
        throw new IllegalArgumentException("Unknown permission action " + name);
    }
}
