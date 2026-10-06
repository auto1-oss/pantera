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

package com.auto1.pantera.http.body;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Incremental JSON string scanner behind {@link JsonStringRewrite}.
 *
 * <p>Tracks just enough structure to know the key path of every string
 * value: a stack of open containers, the current key of the innermost
 * object, and whether the scanner is inside a string (and inside an escape).
 * Numbers, literals and whitespace never need interpreting, so they are
 * skipped byte by byte. One instance serves one subscription; it is not
 * thread-safe.</p>
 *
 * <p>Pass-through bytes are emitted as slices of the chunk they arrived in.
 * A string value selected by a rule is withheld from the output while its
 * bytes (possibly spanning chunks) are collected, then emitted rewritten.</p>
 */
final class JsonStringScanner {

    /**
     * Wildcard rule segment; also the path segment every array element has,
     * so an element is only ever addressed through the wildcard.
     */
    private static final String ANY = "*";

    /**
     * Rules in declaration order.
     */
    private final List<Rule> rules;

    /**
     * Open containers, root first.
     */
    private final List<Frame> frames;

    /**
     * Bytes of the key or selected value currently being read, without quotes.
     */
    private final ByteArrayOutputStream token;

    /**
     * Where the scanner is.
     */
    private Mode mode;

    /**
     * Whether the previous byte inside a string was a backslash.
     */
    private boolean escaped;

    /**
     * Rule the value currently being collected belongs to.
     */
    private UnaryOperator<String> active;

    /**
     * Ctor.
     *
     * @param rules Rewrite function by slash-separated key path
     */
    JsonStringScanner(final Map<String, UnaryOperator<String>> rules) {
        this.rules = new ArrayList<>(rules.size());
        for (final Map.Entry<String, UnaryOperator<String>> rule : rules.entrySet()) {
            this.rules.add(new Rule(rule.getKey().split("/"), rule.getValue()));
        }
        this.frames = new ArrayList<>();
        this.token = new ByteArrayOutputStream();
        this.mode = Mode.STRUCTURE;
    }

    /**
     * Scan one chunk.
     *
     * @param chunk Chunk; consumed in place, its position is not advanced
     * @return Output buffers for this chunk, possibly empty
     */
    List<ByteBuffer> feed(final ByteBuffer chunk) {
        final List<ByteBuffer> out = new ArrayList<>(3);
        final int end = chunk.limit();
        int flushed = chunk.position();
        boolean withheld = this.mode == Mode.CAPTURE;
        for (int pos = chunk.position(); pos < end; pos += 1) {
            final byte current = chunk.get(pos);
            if (this.mode == Mode.STRUCTURE) {
                if (this.structure(current)) {
                    JsonStringScanner.slice(out, chunk, flushed, pos);
                    withheld = true;
                }
            } else if (this.mode == Mode.CAPTURE) {
                if (this.closes(current)) {
                    out.add(ByteBuffer.wrap(this.rewritten()));
                    this.mode = Mode.STRUCTURE;
                    withheld = false;
                    flushed = pos + 1;
                } else {
                    this.token.write(current);
                }
            } else {
                this.insideString(current);
            }
        }
        if (!withheld) {
            JsonStringScanner.slice(out, chunk, flushed, end);
        }
        return out;
    }

    /**
     * Flush whatever an unterminated selected value left behind, unchanged.
     *
     * @return Trailing output buffers, possibly empty
     */
    List<ByteBuffer> finish() {
        final List<ByteBuffer> out = new ArrayList<>(1);
        if (this.mode == Mode.CAPTURE) {
            final ByteArrayOutputStream raw = new ByteArrayOutputStream(this.token.size() + 1);
            raw.write('"');
            raw.writeBytes(this.token.toByteArray());
            out.add(ByteBuffer.wrap(raw.toByteArray()));
            this.token.reset();
            this.mode = Mode.STRUCTURE;
        }
        return out;
    }

    /**
     * Handle one byte outside a string.
     *
     * @param current Byte
     * @return True when a selected value starts here and output must be withheld
     */
    private boolean structure(final byte current) {
        boolean capture = false;
        switch (current) {
            case '"':
                capture = this.openString();
                break;
            case '{':
                this.frames.add(new Frame(this.segment(), false));
                break;
            case '[':
                this.frames.add(new Frame(this.segment(), true));
                break;
            case '}':
            case ']':
                if (!this.frames.isEmpty()) {
                    this.frames.remove(this.frames.size() - 1);
                }
                break;
            case ',':
                if (!this.frames.isEmpty() && !this.top().array) {
                    this.top().key = null;
                    this.top().expectKey = true;
                }
                break;
            default:
                break;
        }
        return capture;
    }

    /**
     * Handle one byte inside a key or a pass-through string.
     *
     * @param current Byte
     */
    private void insideString(final byte current) {
        if (this.closes(current)) {
            if (this.mode == Mode.KEY) {
                this.top().key = this.token.toString(StandardCharsets.UTF_8);
                this.top().expectKey = false;
                this.token.reset();
            }
            this.mode = Mode.STRUCTURE;
        } else if (this.mode == Mode.KEY) {
            this.token.write(current);
        }
    }

    /**
     * A string opens: decide whether it is a key, a selected value or noise.
     *
     * @return True when the value is selected and must be collected
     */
    private boolean openString() {
        this.escaped = false;
        this.token.reset();
        boolean capture = false;
        if (!this.frames.isEmpty() && !this.top().array && this.top().expectKey) {
            this.mode = Mode.KEY;
        } else {
            this.active = this.match();
            if (this.active == null) {
                this.mode = Mode.PASS;
            } else {
                this.mode = Mode.CAPTURE;
                capture = true;
            }
        }
        return capture;
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
     * Rule whose path is the path of the value about to be read.
     *
     * @return Rewrite function, or null when no rule applies
     */
    private UnaryOperator<String> match() {
        UnaryOperator<String> found = null;
        for (final Rule rule : this.rules) {
            if (this.at(rule.path)) {
                found = rule.fn;
                break;
            }
        }
        return found;
    }

    /**
     * Whether the current value path equals a rule path.
     *
     * @param path Rule path
     * @return True when every segment matches
     */
    private boolean at(final String... path) {
        final int depth = this.frames.size();
        boolean same = depth > 0 && path.length == depth;
        for (int idx = 1; same && idx < depth; idx += 1) {
            same = JsonStringScanner.segmentMatches(path[idx - 1], this.frames.get(idx).segment);
        }
        if (same) {
            final Frame top = this.top();
            same = JsonStringScanner.segmentMatches(
                path[depth - 1], top.array ? JsonStringScanner.ANY : top.key
            );
        }
        return same;
    }

    /**
     * Path segment a container opened now is reached by from its parent.
     *
     * @return Segment, or null at the root
     */
    private String segment() {
        final String result;
        if (this.frames.isEmpty()) {
            result = null;
        } else if (this.top().array) {
            result = JsonStringScanner.ANY;
        } else {
            result = this.top().key;
        }
        return result;
    }

    /**
     * Innermost open container.
     *
     * @return Frame
     */
    private Frame top() {
        return this.frames.get(this.frames.size() - 1);
    }

    /**
     * The collected value, rewritten by its rule and quoted.
     *
     * @return JSON string literal bytes
     */
    private byte[] rewritten() {
        final byte[] raw = this.token.toByteArray();
        this.token.reset();
        final String value = JsonStringScanner.unescape(raw);
        final String replaced = this.active.apply(value);
        final ByteArrayOutputStream literal = new ByteArrayOutputStream(raw.length + 2);
        literal.write('"');
        if (value.equals(replaced)) {
            literal.writeBytes(raw);
        } else {
            literal.writeBytes(JsonStringScanner.escape(replaced).getBytes(StandardCharsets.UTF_8));
        }
        literal.write('"');
        return literal.toByteArray();
    }

    /**
     * Whether a rule segment accepts a path segment.
     *
     * @param rule Rule segment
     * @param actual Path segment, possibly null for a malformed document
     * @return True when the rule segment is the wildcard or equal
     */
    private static boolean segmentMatches(final String rule, final String actual) {
        return JsonStringScanner.ANY.equals(rule) || rule.equals(actual);
    }

    /**
     * Emit a slice of a chunk.
     *
     * @param out Output list
     * @param chunk Chunk
     * @param from Start, inclusive
     * @param until End, exclusive
     */
    private static void slice(
        final List<ByteBuffer> out, final ByteBuffer chunk, final int from, final int until
    ) {
        if (until > from) {
            final ByteBuffer dup = chunk.duplicate();
            dup.position(from);
            dup.limit(until);
            out.add(dup.slice());
        }
    }

    /**
     * Decode the raw bytes of a JSON string literal (without quotes).
     *
     * @param raw Raw UTF-8 bytes, escapes intact
     * @return Decoded value
     */
    private static String unescape(final byte[] raw) {
        final String text = new String(raw, StandardCharsets.UTF_8);
        final StringBuilder out = new StringBuilder(text.length());
        int idx = 0;
        while (idx < text.length()) {
            final char chr = text.charAt(idx);
            if (chr == '\\' && idx + 1 < text.length()) {
                idx += JsonStringScanner.unescapeAt(text, idx + 1, out);
            } else {
                out.append(chr);
                idx += 1;
            }
        }
        return out.toString();
    }

    /**
     * Decode one escape sequence.
     *
     * @param text Literal text
     * @param idx Index of the character after the backslash
     * @param out Target
     * @return Number of characters consumed including the backslash
     */
    private static int unescapeAt(final String text, final int idx, final StringBuilder out) {
        int consumed = 2;
        final char code = text.charAt(idx);
        switch (code) {
            case 'n':
                out.append('\n');
                break;
            case 'r':
                out.append('\r');
                break;
            case 't':
                out.append('\t');
                break;
            case 'b':
                out.append('\b');
                break;
            case 'f':
                out.append('\f');
                break;
            case 'u':
                if (idx + 5 <= text.length()) {
                    try {
                        out.append((char) Integer.parseInt(text.substring(idx + 1, idx + 5), 16));
                        consumed = 6;
                    } catch (final NumberFormatException ex) {
                        out.append('\\').append(code);
                    }
                } else {
                    out.append('\\').append(code);
                }
                break;
            default:
                out.append(code);
                break;
        }
        return consumed;
    }

    /**
     * Encode a value as the inside of a JSON string literal.
     *
     * @param value Value
     * @return Escaped text without quotes
     */
    private static String escape(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (int idx = 0; idx < value.length(); idx += 1) {
            final char chr = value.charAt(idx);
            switch (chr) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (chr < ' ') {
                        out.append(String.format("\\u%04x", (int) chr));
                    } else {
                        out.append(chr);
                    }
                    break;
            }
        }
        return out.toString();
    }

    /**
     * Where the scanner is.
     */
    private enum Mode {
        /**
         * Outside any string.
         */
        STRUCTURE,
        /**
         * Inside an object key.
         */
        KEY,
        /**
         * Inside a string value no rule selects.
         */
        PASS,
        /**
         * Inside a string value a rule selects.
         */
        CAPTURE
    }

    /**
     * One rewrite rule.
     */
    private static final class Rule {

        /**
         * Key path.
         */
        private final String[] path;

        /**
         * Rewrite function.
         */
        private final UnaryOperator<String> fn;

        /**
         * Ctor.
         *
         * @param path Key path
         * @param fn Rewrite function
         */
        Rule(final String[] path, final UnaryOperator<String> fn) {
            this.path = path.clone();
            this.fn = fn;
        }
    }

    /**
     * One open container.
     */
    private static final class Frame {

        /**
         * Segment this container is reached by from its parent; null at the root.
         */
        private final String segment;

        /**
         * Whether this is an array.
         */
        private final boolean array;

        /**
         * Current key of an object, null before the first and after a comma.
         */
        private String key;

        /**
         * Whether the next string in an object is a key.
         */
        private boolean expectKey;

        /**
         * Ctor.
         *
         * @param segment Segment from the parent
         * @param array Whether this is an array
         */
        Frame(final String segment, final boolean array) {
            this.segment = segment;
            this.array = array;
            this.expectKey = !array;
        }
    }
}
