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
package com.auto1.pantera.tools;

import java.util.Objects;

/**
 * Class stores classname and it's compiled byte code.
 * @since 0.28.0
 */
public final class CodeBlob {
    /**
     * Class name of class.
     * It is used by class loader as classname.
     */
    private final String classname;

    /**
     * Byte code of class.
     */
    private final byte[] blob;

    /**
     * Ctor.
     * @param classname Class name of class.
     * @param bytes Byte code of class
     */
    public CodeBlob(final String classname, final byte[] bytes) {
        this.classname = classname;
        this.blob = bytes;
    }

    /**
     * Class name of class.
     * @return Class name.
     */
    public String classname() {
        return this.classname;
    }

    /**
     * Byte code of class.
     * @return Byte code.
     */
    public byte[] blob() {
        return this.blob;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final CodeBlob other = (CodeBlob) obj;
        return Objects.equals(this.classname, other.classname);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.classname);
    }
}
