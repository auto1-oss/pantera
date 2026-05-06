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
package com.auto1.pantera.api.perms;

import com.auto1.pantera.security.perms.Action;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

/**
 * Api actions.
 * @since 0.30
 */
public abstract class ApiActions {

    /**
     * Action values list.
     */
    private final Collection<Action> values;

    /**
     * Ctor.
     * @param values Action values list
     */
    protected ApiActions(final Action[] values) {
        this.values = Arrays.asList(values);
    }

    /**
     * Returns action, that represents all (action tha allows any action) actions.
     * @return Action all
     */
    abstract Action all();

    /**
     * All supported actions list.
     * @return All supported actions
     */
    Collection<Action> list() {
        return this.values;
    }

    /**
     * Obtain mask by action string name.
     * @return Mask by string action
     */
    Function<String, Integer> maskByAction() {
        return str -> {
            for (final Action item : this.values) {
                if (item.names().contains(str)) {
                    return item.mask();
                }
            }
            throw new IllegalArgumentException(
                String.format("Unknown api repo permission action %s", str)
            );
        };
    }
}
