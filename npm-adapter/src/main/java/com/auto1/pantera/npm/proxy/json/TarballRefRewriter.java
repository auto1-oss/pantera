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
package com.auto1.pantera.npm.proxy.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Incremental, byte-preserving equivalent of {@link CachedContent#valueString()}.
 *
 * <p>{@link CachedContent} rewrites every match of
 * {@code ("tarball"\s*:\s*")([^"]+)(")} over the whole packument held as one
 * String. This class produces the same bytes while the upstream body flows
 * through it chunk by chunk, so the packument is never materialised: memory
 * is one chunk plus the tarball value being rewritten.</p>
 *
 * <p>It tracks just enough JSON lexing to recognise the same matches on a
 * well-formed document: a string token whose raw bytes are {@code tarball}
 * (or end in an escaped quote followed by {@code tarball}, the only other way
 * the regex can see {@code "tarball"} followed by a colon), then optional
 * whitespace, a colon, optional whitespace and a string value. The raw value
 * up to its first quote byte is handed to the very same
 * {@link CachedContent#transformRef(String)} the String path uses; the rest of
 * the document is copied byte for byte. Like the regex, the key may sit at
 * any depth (not only {@code versions/*}{@code /dist/tarball}), escapes are
 * NOT decoded (so {@code https:\/\/host\/pkg\/-\/a.tgz} stays unchanged, as it
 * does today) and an empty value is left alone.</p>
 *
 * <p>One instance serves one document; it is not thread-safe.</p>
 *
 * @since 2.2.11
 */
public final class TarballRefRewriter {

    /**
     * Raw key bytes the regex anchors on.
     */
    private static final byte[] KEY = "tarball".getBytes(StandardCharsets.US_ASCII);

    /**
     * Size of the rolling window over the raw bytes of the current string:
     * {@code "tarball} (escaped quote + key) is the longest suffix checked.
     */
    private static final int TAIL = 8;

    /**
     * Ref transformation shared with the String implementation.
     */
    private final CachedContent refs;

    /**
     * Last {@link #TAIL} raw bytes of the current string, as a ring.
     */
    private final byte[] tail;

    /**
     * Raw bytes of the tarball value being collected, quotes excluded.
     */
    private final ByteArrayOutputStream value;

    /**
     * Reusable copy of the chunk being scanned.
     */
    private byte[] scratch;

    /**
     * Lexer state.
     */
    private State state;

    /**
     * Whether the previous byte inside a string was an escaping backslash.
     */
    private boolean escaped;

    /**
     * Raw length of the current string.
     */
    private long length;

    /**
     * Ctor.
     *
     * @param pkg Package name, as passed to {@link CachedContent}
     */
    public TarballRefRewriter(final String pkg) {
        this.refs = new CachedContent("", pkg);
        this.tail = new byte[TarballRefRewriter.TAIL];
        this.value = new ByteArrayOutputStream();
        this.scratch = new byte[0];
        this.state = State.OUTSIDE;
    }

    /**
     * Rewrite one chunk.
     *
     * @param chunk Upstream chunk; its position is not advanced
     * @param out Destination of the rewritten bytes
     * @throws IOException When the destination fails
     */
    public void write(final ByteBuffer chunk, final OutputStream out) throws IOException {
        final int len = chunk.remaining();
        if (this.scratch.length < len) {
            this.scratch = new byte[len];
        }
        chunk.duplicate().get(this.scratch, 0, len);
        int flushed = 0;
        for (int pos = 0; pos < len; pos += 1) {
            final byte current = this.scratch[pos];
            if (this.state == State.CAPTURE) {
                if (this.closes(current)) {
                    out.write(this.rewritten());
                    this.state = State.OUTSIDE;
                    flushed = pos;
                } else {
                    this.value.write(current);
                }
            } else if (this.step(current)) {
                out.write(this.scratch, flushed, pos + 1 - flushed);
                flushed = pos + 1;
            }
        }
        if (this.state != State.CAPTURE) {
            out.write(this.scratch, flushed, len - flushed);
        }
    }

    /**
     * Flush a tarball value the document ended inside of, unchanged (the
     * regex needs the closing quote to match, so a truncated value passes).
     *
     * @param out Destination
     * @throws IOException When the destination fails
     */
    public void finish(final OutputStream out) throws IOException {
        if (this.state == State.CAPTURE) {
            this.value.writeTo(out);
            this.value.reset();
            this.state = State.OUTSIDE;
        }
    }

    /**
     * Advance the lexer by one byte outside a collected value.
     *
     * @param current Byte
     * @return True when this byte opens a tarball value to collect
     */
    private boolean step(final byte current) {
        boolean opens = false;
        switch (this.state) {
            case STRING:
                this.inString(current);
                break;
            case AFTER_KEY:
                if (current == ':') {
                    this.state = State.AFTER_COLON;
                } else if (!TarballRefRewriter.whitespace(current)) {
                    this.outside(current);
                }
                break;
            case AFTER_COLON:
                if (current == '"') {
                    this.state = State.CAPTURE;
                    this.escaped = false;
                    this.value.reset();
                    opens = true;
                } else if (!TarballRefRewriter.whitespace(current)) {
                    this.outside(current);
                }
                break;
            default:
                this.outside(current);
                break;
        }
        return opens;
    }

    /**
     * Handle a byte outside any string.
     *
     * @param current Byte
     */
    private void outside(final byte current) {
        if (current == '"') {
            this.state = State.STRING;
            this.escaped = false;
            this.length = 0L;
        } else {
            this.state = State.OUTSIDE;
        }
    }

    /**
     * Handle a byte inside a string that is not a collected value.
     *
     * @param current Byte
     */
    private void inString(final byte current) {
        if (this.closes(current)) {
            if (this.keyed()) {
                this.state = State.AFTER_KEY;
            } else {
                this.state = State.OUTSIDE;
            }
        } else {
            this.tail[(int) (this.length & (TarballRefRewriter.TAIL - 1))] = current;
            this.length += 1L;
        }
    }

    /**
     * Advance the in-string escape state.
     *
     * @param current Byte inside a string
     * @return True when this byte is the closing quote
     */
    private boolean closes(final byte current) {
        boolean closed = false;
        if (this.escaped) {
            this.escaped = false;
        } else if (current == '\\') {
            this.escaped = true;
        } else if (current == '"') {
            closed = true;
        }
        return closed;
    }

    /**
     * Whether the string just closed reads {@code "tarball"} to the regex:
     * raw content {@code tarball}, or raw content ending in an (escaped)
     * quote followed by {@code tarball}.
     *
     * @return True for a tarball key candidate
     */
    private boolean keyed() {
        final int keylen = TarballRefRewriter.KEY.length;
        boolean match = this.length == keylen
            || this.length > keylen && this.back(keylen + 1) == '"';
        for (int idx = 0; match && idx < keylen; idx += 1) {
            match = this.back(keylen - idx) == TarballRefRewriter.KEY[idx];
        }
        return match;
    }

    /**
     * Raw byte of the current string, counted back from its end.
     *
     * @param distance 1 for the last byte
     * @return Byte
     */
    private byte back(final int distance) {
        return this.tail[(int) ((this.length - distance) & (TarballRefRewriter.TAIL - 1))];
    }

    /**
     * The collected value, rewritten like {@code CachedContent} does: the
     * regex group is the raw value up to its first quote byte (an escaped
     * quote ends it too) and must not be empty.
     *
     * @return Raw value bytes to emit, closing quote excluded
     */
    private byte[] rewritten() {
        final byte[] raw = this.value.toByteArray();
        this.value.reset();
        int end = 0;
        while (end < raw.length && raw[end] != '"') {
            end += 1;
        }
        byte[] result = raw;
        if (end > 0) {
            final String ref = new String(raw, 0, end, StandardCharsets.UTF_8);
            final String replaced = this.refs.transformRef(ref);
            if (!replaced.equals(ref)) {
                final byte[] head = replaced.getBytes(StandardCharsets.UTF_8);
                result = new byte[head.length + raw.length - end];
                System.arraycopy(head, 0, result, 0, head.length);
                System.arraycopy(raw, end, result, head.length, raw.length - end);
            }
        }
        return result;
    }

    /**
     * Whether a byte is whitespace to {@code \s} of the regex.
     *
     * @param current Byte
     * @return True for space, tab, LF, VT, FF or CR
     */
    private static boolean whitespace(final byte current) {
        return current == ' ' || current == '\t' || current == '\n'
            || current == 0x0B || current == '\f' || current == '\r';
    }

    /**
     * Lexer state.
     */
    private enum State {
        /**
         * Outside any string.
         */
        OUTSIDE,
        /**
         * Inside a string that is not a collected value.
         */
        STRING,
        /**
         * After a tarball key candidate, before the colon.
         */
        AFTER_KEY,
        /**
         * After the colon of a tarball key, before its value.
         */
        AFTER_COLON,
        /**
         * Inside a tarball string value being collected.
         */
        CAPTURE
    }
}
