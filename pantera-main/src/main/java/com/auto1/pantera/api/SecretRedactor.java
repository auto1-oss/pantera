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
package com.auto1.pantera.api;

import java.util.Locale;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonStructure;
import javax.json.JsonValue;

/**
 * Redaction boundary between persisted configuration and the read API.
 *
 * <p>Repository and storage-alias configuration is persisted as the caller
 * submitted it — including upstream proxy passwords ({@code remotes[].password}),
 * storage backend credentials ({@code secretAccessKey}, {@code sessionToken},
 * tokens, API keys). Before 2.2.9 the read endpoints returned that persisted
 * document verbatim, so a plain read grant disclosed every secret. This class
 * walks a JSON structure and replaces the value of every secret-bearing key
 * with {@link #MASK}, recursively (objects, arrays, nested credential blocks).</p>
 *
 * <p>Secrets stay write-only: {@link #restoreMasked} lets an update that
 * round-trips the masked value (a UI edit of an unrelated field) keep the
 * stored secret instead of overwriting it with the sentinel.</p>
 *
 * <p>Key matching is by substring on the lower-cased key. Bare {@code key}
 * is deliberately NOT a trigger (it would mask structural fields); the
 * credential shapes Pantera actually persists are covered by the tokens
 * below.</p>
 *
 * @since 2.2.9
 */
public final class SecretRedactor {

    /**
     * Sentinel written in place of a secret value.
     */
    public static final String MASK = "***";

    /**
     * Lower-cased key fragments that mark a value as secret.
     */
    private static final String[] SECRET_MARKERS = {
        "password", "passwd", "pwd", "secret", "token", "accesskey",
        "apikey", "privatekey", "credential",
    };

    /**
     * Whether a JSON key names a secret-bearing value.
     * @param key JSON object key
     * @return {@code true} when the value under this key must be masked
     */
    public boolean isSecretKey(final String key) {
        final String lower = key.toLowerCase(Locale.ROOT);
        boolean secret = false;
        for (final String marker : SECRET_MARKERS) {
            if (lower.contains(marker)) {
                secret = true;
                break;
            }
        }
        return secret;
    }

    /**
     * Return a copy of {@code value} with every secret-bearing string
     * replaced by {@link #MASK}. Non-string values under a secret key
     * (an object such as a {@code credentials} block) are recursed into,
     * not blanked, so their non-secret members survive.
     * @param value Structure to redact
     * @return Redacted copy
     */
    public JsonStructure redact(final JsonStructure value) {
        return (JsonStructure) this.redactValue(value, false);
    }

    /**
     * Merge an incoming (possibly masked) document over the stored one:
     * wherever the incoming document carries {@link #MASK} under a secret
     * key and the stored document has a real value at the same path, the
     * stored value is kept. Everything else comes from the incoming
     * document, so a caller can still change or clear a secret explicitly.
     * @param incoming Document submitted by the client
     * @param stored Document currently persisted (may be {@code null})
     * @return Document to persist
     */
    public JsonObject restoreMasked(final JsonObject incoming, final JsonObject stored) {
        if (stored == null) {
            return incoming;
        }
        return (JsonObject) this.mergeValue(incoming, stored);
    }

    /**
     * Recursive redaction.
     * @param value Node
     * @param underSecretKey Whether an ancestor key was secret (strings
     *  at any depth below a secret key are masked)
     * @return Redacted node
     */
    private JsonValue redactValue(final JsonValue value, final boolean underSecretKey) {
        final JsonValue result;
        switch (value.getValueType()) {
            case OBJECT:
                result = this.redactObject((JsonObject) value);
                break;
            case ARRAY:
                result = this.redactArray((JsonArray) value, underSecretKey);
                break;
            case STRING:
                result = underSecretKey ? Json.createValue(MASK) : value;
                break;
            default:
                result = value;
                break;
        }
        return result;
    }

    private JsonObject redactObject(final JsonObject obj) {
        final JsonObjectBuilder out = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> entry : obj.entrySet()) {
            final boolean secret = this.isSecretKey(entry.getKey());
            final JsonValue child = entry.getValue();
            if (secret && child.getValueType() == JsonValue.ValueType.STRING) {
                out.add(entry.getKey(), MASK);
            } else {
                out.add(entry.getKey(), this.redactValue(child, secret));
            }
        }
        return out.build();
    }

    private JsonArray redactArray(final JsonArray arr, final boolean underSecretKey) {
        final JsonArrayBuilder out = Json.createArrayBuilder();
        for (final JsonValue item : arr) {
            out.add(this.redactValue(item, underSecretKey));
        }
        return out.build();
    }

    /**
     * Recursive sentinel-restoring merge (incoming wins unless it is the
     * mask under a secret key).
     * @param incoming Incoming node
     * @param stored Stored node at the same path (may be {@code null})
     * @return Merged node
     */
    private JsonValue mergeValue(final JsonValue incoming, final JsonValue stored) {
        final JsonValue result;
        if (stored != null
            && incoming.getValueType() == JsonValue.ValueType.OBJECT
            && stored.getValueType() == JsonValue.ValueType.OBJECT) {
            result = this.mergeObject((JsonObject) incoming, (JsonObject) stored);
        } else if (stored != null
            && incoming.getValueType() == JsonValue.ValueType.ARRAY
            && stored.getValueType() == JsonValue.ValueType.ARRAY) {
            result = this.mergeArray((JsonArray) incoming, (JsonArray) stored);
        } else {
            result = incoming;
        }
        return result;
    }

    private JsonObject mergeObject(final JsonObject incoming, final JsonObject stored) {
        final JsonObjectBuilder out = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> entry : incoming.entrySet()) {
            final String key = entry.getKey();
            final JsonValue value = entry.getValue();
            final JsonValue previous = stored.get(key);
            if (this.isSecretKey(key) && SecretRedactor.isMask(value) && previous != null) {
                out.add(key, previous);
            } else {
                out.add(key, this.mergeValue(value, previous));
            }
        }
        return out.build();
    }

    private JsonArray mergeArray(final JsonArray incoming, final JsonArray stored) {
        final JsonArrayBuilder out = Json.createArrayBuilder();
        for (int idx = 0; idx < incoming.size(); idx = idx + 1) {
            final JsonValue previous = idx < stored.size() ? stored.get(idx) : null;
            out.add(this.mergeValue(incoming.get(idx), previous));
        }
        return out.build();
    }

    private static boolean isMask(final JsonValue value) {
        return value.getValueType() == JsonValue.ValueType.STRING
            && MASK.equals(((JsonString) value).getString());
    }
}
