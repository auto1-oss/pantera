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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Accept;
import com.auto1.pantera.http.headers.ContentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * A Hex API response body: a map with string keys and string or integer
 * values, in the representation the client asked for.
 *
 * <p>The Hex client ({@code mix hex.publish}, hex_core) asks for
 * {@code application/vnd.hex+erlang} and decodes the body with
 * {@code binary_to_term}: an empty or non-term body fails with
 * {@code invalid_term} even on success. The map is therefore encoded in the
 * Erlang external term format (binaries for strings), or as JSON when the
 * client asked for JSON.</p>
 *
 * @since 2.2.9
 */
final class HexResponseBody {

    /**
     * Erlang content type.
     */
    private static final String ERLANG = "application/vnd.hex+erlang";

    /**
     * External term format version.
     */
    private static final int VERSION = 131;

    /**
     * MAP_EXT tag.
     */
    private static final int MAP_EXT = 116;

    /**
     * BINARY_EXT tag.
     */
    private static final int BINARY_EXT = 109;

    /**
     * SMALL_INTEGER_EXT tag.
     */
    private static final int SMALL_INTEGER_EXT = 97;

    /**
     * INTEGER_EXT tag.
     */
    private static final int INTEGER_EXT = 98;

    /**
     * JSON mapper (thread-safe once configured).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Request headers.
     */
    private final Headers request;

    /**
     * Fields, in the order they are encoded.
     */
    private final Map<String, Object> fields;

    /**
     * Ctor.
     * @param request Request headers
     * @param fields String or integer values by key
     */
    HexResponseBody(final Headers request, final Map<String, Object> fields) {
        this.request = request;
        this.fields = fields;
    }

    /**
     * Response with this body.
     * @param status Status
     * @return Response
     */
    Response response(final RsStatus status) {
        final ResponseBuilder res = ResponseBuilder.from(status);
        if (this.json()) {
            res.jsonBody(this.encoded());
        } else {
            res.header(ContentType.mime(HexResponseBody.ERLANG)).body(this.term());
        }
        return res.build();
    }

    /**
     * The map as JSON.
     * @return JSON text
     */
    private String encoded() {
        try {
            return HexResponseBody.MAPPER.writeValueAsString(this.fields);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode a Hex response", ex);
        }
    }

    /**
     * Whether the client asked for JSON rather than an Erlang term.
     * @return True for JSON
     */
    private boolean json() {
        return this.request.values(Accept.NAME).stream()
            .anyMatch(val -> val.toLowerCase(Locale.ROOT).contains("json"));
    }

    /**
     * The map in the Erlang external term format.
     * @return Encoded term
     */
    private byte[] term() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HexResponseBody.VERSION);
        out.write(HexResponseBody.MAP_EXT);
        HexResponseBody.int32(out, this.fields.size());
        this.fields.forEach(
            (key, val) -> {
                HexResponseBody.binary(out, key);
                if (val instanceof Integer) {
                    HexResponseBody.integer(out, (Integer) val);
                } else {
                    HexResponseBody.binary(out, String.valueOf(val));
                }
            }
        );
        return out.toByteArray();
    }

    /**
     * Write a UTF-8 binary.
     * @param out Output
     * @param value Value
     */
    private static void binary(final ByteArrayOutputStream out, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(HexResponseBody.BINARY_EXT);
        HexResponseBody.int32(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Write an integer.
     * @param out Output
     * @param value Value
     */
    private static void integer(final ByteArrayOutputStream out, final int value) {
        if (value >= 0 && value <= 255) {
            out.write(HexResponseBody.SMALL_INTEGER_EXT);
            out.write(value);
        } else {
            out.write(HexResponseBody.INTEGER_EXT);
            HexResponseBody.int32(out, value);
        }
    }

    /**
     * Write a big-endian 32-bit integer.
     * @param out Output
     * @param value Value
     */
    private static void int32(final ByteArrayOutputStream out, final int value) {
        out.write(ByteBuffer.allocate(Integer.BYTES).putInt(value).array(), 0, Integer.BYTES);
    }
}
