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
package com.auto1.pantera.asto;

import java.nio.file.NoSuchFileException;
import java.util.Locale;

/**
 * Tells whether a failed {@link Storage#delete(Key)} only says that the key
 * was not there. Storages report it in different ways:
 * {@link ValueNotFoundException}, {@link NoSuchFileException} or an I/O error
 * whose message reads "Key does not exist".
 *
 * @since 2.2.14
 */
public final class AbsentKey {

    /**
     * Message of the storages that have no exception type for it.
     */
    private static final String MESSAGE = "does not exist";

    /**
     * Failure of the delete.
     */
    private final Throwable failure;

    /**
     * Ctor.
     * @param failure Failure of the delete
     */
    public AbsentKey(final Throwable failure) {
        this.failure = failure;
    }

    /**
     * Whether the failure means "no such key".
     * @return True if so
     */
    public boolean reported() {
        boolean absent = false;
        Throwable cur = this.failure;
        int depth = 0;
        while (cur != null && !absent && depth < 16) {
            absent = cur instanceof ValueNotFoundException
                || cur instanceof NoSuchFileException
                || AbsentKey.says(cur.getMessage());
            cur = cur.getCause();
            depth += 1;
        }
        return absent;
    }

    /**
     * Whether a message reads "does not exist".
     * @param msg Message or null
     * @return True if so
     */
    private static boolean says(final String msg) {
        return msg != null && msg.toLowerCase(Locale.ROOT).contains(AbsentKey.MESSAGE);
    }
}
