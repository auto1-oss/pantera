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

import java.util.List;

/**
 * Byte array wrapper with ability to transform it to
 * boxed and primitive array.
 *
 * @since 0.7
 */
public final class ByteArray {

    /**
     * Bytes.
     */
    private final Byte[] bytes;

    /**
     * Ctor for a list of byes.
     *
     * @param bytes The list of bytes
     */
    public ByteArray(final List<Byte> bytes) {
        this(fromList(bytes));
    }

    /**
     * Ctor for a primitive array.
     *
     * @param bytes The primitive bytes
     */
    public ByteArray(final byte[] bytes) {
        this(boxed(bytes));
    }

    /**
     * Ctor.
     *
     * @param bytes The bytes.
     */
    public ByteArray(final Byte[] bytes) {
        this.bytes = bytes; // NOPMD ArrayIsStoredDirectly - immutable holder for an already-boxed array; primitiveBytes() returns a copy and boxedBytes() exposes the same internal pattern by design
    }

    /**
     * Return primitive byte array.
     *
     * @return Primitive byte array
     */
    public byte[] primitiveBytes() {
        final byte[] result = new byte[this.bytes.length];
        for (int itr = 0; itr < this.bytes.length; itr += 1) {
            result[itr] = this.bytes[itr];
        }
        return result;
    }

    /**
     * Return primitive byte array.
     *
     * @return Primitive byte array
     */
    public Byte[] boxedBytes() {
        return this.bytes; // NOPMD MethodReturnsInternalArray - public API contract: returns the internal boxed array view; callers historically may mutate, defensive copy would be a behavior change
    }

    /**
     * Convert primitive to boxed array.
     * @param primitive Primitive byte array
     * @return Boxed byte array
     */
    private static Byte[] boxed(final byte[] primitive) {
        final Byte[] res = new Byte[primitive.length];
        // Boxing byte -> Byte requires per-element auto-boxing; cannot use
        // Arrays.copyOf or System.arraycopy across primitive/reference types.
        for (int itr = 0; itr < primitive.length; itr += 1) { // NOPMD AvoidArrayLoops - byte[] -> Byte[] requires per-element auto-boxing
            res[itr] = primitive[itr];
        }
        return res;
    }

    /**
     * Convert list of bytes to byte array.
     * @param list The list of bytes.
     * @return Boxed byte array
     */
    private static Byte[] fromList(final List<Byte> list) {
        return list.toArray(new Byte[0]);
    }
}
