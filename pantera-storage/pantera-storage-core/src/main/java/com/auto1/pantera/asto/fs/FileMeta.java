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
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.asto.Meta;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata for file.
 * @since 1.9
 */
final class FileMeta implements Meta {

    /**
     * File attributes.
     */
    private final BasicFileAttributes attr;

    /**
     * New metadata.
     * @param attr File attributes
     */
    FileMeta(final BasicFileAttributes attr) {
        this.attr = attr;
    }

    @Override
    public <T> T read(final ReadOperator<T> opr) {
        final Map<String, String> raw = new HashMap<>();
        Meta.OP_SIZE.put(raw, this.attr.size());
        Meta.OP_ACCESSED_AT.put(raw, this.attr.lastAccessTime().toInstant());
        Meta.OP_CREATED_AT.put(raw, this.attr.creationTime().toInstant());
        Meta.OP_UPDATED_AT.put(raw, this.attr.lastModifiedTime().toInstant());
        return opr.take(raw);
    }
}
