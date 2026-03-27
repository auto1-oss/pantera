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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Classloader of dynamically compiled classes.
 * @since 0.28
 */
@SuppressWarnings("PMD.ConstructorShouldDoInitialization")
public final class CodeClassLoader extends ClassLoader {
    /**
     * Code blobs.
     */
    private final Map<String, CodeBlob> blobs = new TreeMap<>();

    /**
     * Ctor.
     */
    public CodeClassLoader() {
        super();
    }

    /**
     * Ctor.
     * @param parent Parent class loader.
     */
    public CodeClassLoader(final ClassLoader parent) {
        super(parent);
    }

    /**
     * Adds code blobs.
     * @param blobs Code blobs.
     */
    public void addBlobs(final CodeBlob... blobs) {
        this.addBlobs(List.of(blobs));
    }

    /**
     * Adds code blobs.
     * @param blobs Code blobs.
     */
    public void addBlobs(final List<CodeBlob> blobs) {
        blobs.forEach(blob -> this.blobs.put(blob.classname(), blob));
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        final Class<?> clazz;
        if (this.blobs.containsKey(name)) {
            final byte[] code = this.blobs.get(name).blob();
            clazz = defineClass(name, code, 0, code.length);
        } else {
            clazz = super.findClass(name);
        }
        return clazz;
    }
}
