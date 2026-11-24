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
package com.auto1.pantera.cache;

import com.auto1.pantera.http.context.CallerContext;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cache keys grouped under an index SET, so that "every key of this
 * package" is one read of that SET instead of a {@code SCAN} over the whole
 * keyspace — whose cost grows with every key any cache has written.
 *
 * <p>Both multi-key steps are Lua scripts and therefore atomic on the
 * server ({@code MULTI} is not usable on the shared, multiplexed
 * connection):</p>
 * <ul>
 *   <li>{@link #put} lists the key in the index and stores the value in one
 *       step, and only ever raises the index's time to live — a key that is
 *       missing from its index cannot exist;</li>
 *   <li>{@link #drop} deletes the listed keys and their index entries in
 *       one step;</li>
 *   <li>an index entry whose key is gone (expired, deleted directly) is
 *       harmless, and the index itself expires shortly after its newest
 *       key.</li>
 * </ul>
 *
 * @since 2.2.14
 */
public final class ValkeyKeyIndex {

    /**
     * How much longer than its newest key an index lives, seconds.
     */
    private static final long INDEX_MARGIN = 60L;

    /**
     * {@code KEYS[1]} index, {@code KEYS[2]} key; {@code ARGV[1]} key TTL,
     * {@code ARGV[2]} index TTL, {@code ARGV[3]} value.
     */
    private static final String PUT = String.join(
        "\n",
        "redis.call('SADD', KEYS[1], KEYS[2])",
        "local keep = tonumber(ARGV[2])",
        "if redis.call('TTL', KEYS[1]) < keep then",
        "  redis.call('EXPIRE', KEYS[1], keep)",
        "end",
        "redis.call('SETEX', KEYS[2], tonumber(ARGV[1]), ARGV[3])",
        "return 1"
    );

    /**
     * {@code KEYS[1]} index; {@code ARGV[1]} key prefix (empty = all).
     * Returns a flat list of {@code key, existed} pairs.
     */
    private static final String DROP = String.join(
        "\n",
        "local out = {}",
        "local prefix = ARGV[1]",
        "for _, key in ipairs(redis.call('SMEMBERS', KEYS[1])) do",
        "  if prefix == '' or string.sub(key, 1, #prefix) == prefix then",
        "    out[#out + 1] = key",
        "    out[#out + 1] = tostring(redis.call('DEL', key))",
        "    redis.call('SREM', KEYS[1], key)",
        "  end",
        "end",
        "return out"
    );

    /**
     * {@code KEYS[1]} marker; {@code ARGV[1]} value to set when absent.
     */
    private static final String SINCE = String.join(
        "\n",
        "redis.call('SETNX', KEYS[1], ARGV[1])",
        "return redis.call('GET', KEYS[1])"
    );

    /**
     * Commands.
     */
    private final RedisAsyncCommands<String, byte[]> valkey;

    /**
     * Ctor.
     *
     * @param valkey Commands of the shared connection
     */
    public ValkeyKeyIndex(final RedisAsyncCommands<String, byte[]> valkey) {
        this.valkey = valkey;
    }

    /**
     * Store a value and list its key in an index, atomically.
     *
     * @param index Index key
     * @param key Key
     * @param ttlSeconds Time to live of the key, seconds
     * @param value Value
     * @return Completion of the write
     */
    public CompletableFuture<Void> put(
        final String index, final String key, final long ttlSeconds, final byte[] value
    ) {
        return ValkeyKeyIndex.resumed(this.valkey.<Long>eval(
            ValkeyKeyIndex.PUT,
            ScriptOutputType.INTEGER,
            new String[] {index, key},
            ValkeyKeyIndex.ascii(Long.toString(ttlSeconds)),
            ValkeyKeyIndex.ascii(Long.toString(ttlSeconds + ValkeyKeyIndex.INDEX_MARGIN)),
            value
        )).thenApply(ignored -> null);
    }

    /**
     * Delete every key the index lists that starts with {@code prefix},
     * together with its index entry, atomically.
     *
     * @param index Index key
     * @param prefix Key prefix to match; empty matches every listed key
     * @return What was dropped
     */
    public CompletableFuture<Dropped> drop(final String index, final String prefix) {
        return ValkeyKeyIndex.resumed(this.valkey.<List<Object>>eval(
            ValkeyKeyIndex.DROP,
            ScriptOutputType.MULTI,
            new String[] {index},
            prefix.getBytes(StandardCharsets.UTF_8)
        )).thenApply(ValkeyKeyIndex::dropped);
    }

    /**
     * Keys an index lists.
     *
     * @param index Index key
     * @return Listed keys; some may have expired already
     */
    public CompletableFuture<List<String>> members(final String index) {
        return ValkeyKeyIndex.resumed(this.valkey.smembers(index)).thenApply(
            raw -> raw.stream().map(ValkeyKeyIndex::text).toList()
        );
    }

    /**
     * Cluster-wide "first seen" time: the value the first caller stored
     * under {@code marker}, which every later caller on any node gets back.
     *
     * @param marker Marker key (never expires)
     * @param now Epoch seconds to store when the marker is absent
     * @return Epoch seconds of the first call
     */
    public CompletableFuture<Long> since(final String marker, final long now) {
        return ValkeyKeyIndex.resumed(this.valkey.<byte[]>eval(
            ValkeyKeyIndex.SINCE,
            ScriptOutputType.VALUE,
            new String[] {marker},
            ValkeyKeyIndex.ascii(Long.toString(now))
        )).thenApply(raw -> Long.parseLong(ValkeyKeyIndex.text(raw)));
    }

    /**
     * Hand a Valkey answer back to the caller: the continuation runs on a
     * Lettuce event loop, under the caller's logging context, and the loop
     * gets its own context back afterwards.
     * @param answer Answer of Valkey
     * @param <T> Result type
     * @return Future to chain on
     */
    private static <T> CompletableFuture<T> resumed(
        final java.util.concurrent.CompletionStage<T> answer
    ) {
        return new CallerContext().resume(answer);
    }

    /**
     * Decode the flat {@code key, existed} reply of the drop script.
     *
     * @param reply Script reply
     * @return Dropped keys and how many existed
     */
    private static Dropped dropped(final List<Object> reply) {
        final List<String> keys = new ArrayList<>(reply.size() / 2);
        int deleted = 0;
        for (int idx = 0; idx + 1 < reply.size(); idx = idx + 2) {
            keys.add(ValkeyKeyIndex.text(reply.get(idx)));
            if ("1".equals(ValkeyKeyIndex.text(reply.get(idx + 1)))) {
                deleted = deleted + 1;
            }
        }
        return new Dropped(keys, deleted);
    }

    /**
     * Text of a reply element the binary value codec handed over.
     *
     * @param raw Reply element
     * @return Text
     */
    private static String text(final Object raw) {
        final String result;
        if (raw instanceof byte[] bytes) {
            result = new String(bytes, StandardCharsets.UTF_8);
        } else {
            result = String.valueOf(raw);
        }
        return result;
    }

    /**
     * Script argument bytes.
     *
     * @param value Text
     * @return Bytes
     */
    private static byte[] ascii(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Result of {@link ValkeyKeyIndex#drop}.
     *
     * @param keys Every key that was listed and matched
     * @param deleted How many of them still existed
     */
    public record Dropped(List<String> keys, int deleted) {
    }
}
