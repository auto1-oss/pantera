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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.io.StringReader;
import javax.json.Json;
import javax.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link IndexGenerator}. The key invariant is
 * that after each upload, BOTH the PEP 503 HTML and PEP 691 JSON
 * variants of the package index are persisted side-by-side. Serving
 * either format then becomes a single {@code storage.value} read
 * in {@link SliceIndex}, with no per-request regeneration.
 *
 * <p>Before this was enforced, {@code SliceIndex} would either
 * (a) serve the HTML bytes with a JSON content-type header — causing
 * pip to fail with {@code json.decoder.JSONDecodeError: Expecting
 * value: line 1 column 1 (char 0)} — or (b) regenerate the JSON on
 * every request, which scales linearly with file count and thrashes
 * the storage layer during pip's resolver rounds.</p>
 */
class IndexGeneratorTest {

    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void writesBothHtmlAndJsonSideBySideForPackage() {
        // Package with two artifacts under a version subdirectory
        this.storage.save(
            new Key.From("hello", "0.2.0", "hello-0.2.0-py3-none-any.whl"),
            new Content.From("whl-content".getBytes())
        ).join();
        this.storage.save(
            new Key.From("hello", "0.2.0", "hello-0.2.0.tar.gz"),
            new Content.From("tgz-content".getBytes())
        ).join();

        new IndexGenerator(
            this.storage,
            new Key.From("hello"),
            "/pypi/hello"
        ).generate().join();

        // Both variants must exist
        assertTrue(
            this.storage.exists(new Key.From(".pypi", "hello", "hello.html")).join(),
            "HTML index must be persisted"
        );
        assertTrue(
            this.storage.exists(new Key.From(".pypi", "hello", "hello.json")).join(),
            "JSON index must be persisted alongside HTML"
        );
    }

    @Test
    void writtenJsonIsValidPep691() {
        this.storage.save(
            new Key.From("hello", "0.2.0", "hello-0.2.0-py3-none-any.whl"),
            new Content.From("whl-content".getBytes())
        ).join();

        new IndexGenerator(
            this.storage,
            new Key.From("hello"),
            "/pypi/hello"
        ).generate().join();

        final Content jsonContent = this.storage.value(
            new Key.From(".pypi", "hello", "hello.json")
        ).join();
        final String body = readBody(jsonContent);

        // Must parse as a PEP 691 JSON object
        final JsonObject parsed = Json.createReader(new StringReader(body)).readObject();
        assertEquals("hello", parsed.getString("name"));
        assertEquals("1.1", parsed.getJsonObject("meta").getString("api-version"));
        assertEquals(1, parsed.getJsonArray("files").size());
        final JsonObject entry = parsed.getJsonArray("files").getJsonObject(0);
        assertEquals("hello-0.2.0-py3-none-any.whl", entry.getString("filename"));
        assertNotNull(entry.getJsonObject("hashes").getString("sha256"));
        assertFalse(
            entry.getString("url").isEmpty(),
            "file url must be populated"
        );
    }

    @Test
    void writtenHtmlHasPep503Shape() {
        this.storage.save(
            new Key.From("hello", "0.2.0", "hello-0.2.0.whl"),
            new Content.From("whl".getBytes())
        ).join();

        new IndexGenerator(
            this.storage,
            new Key.From("hello"),
            "/pypi/hello"
        ).generate().join();

        final Content htmlContent = this.storage.value(
            new Key.From(".pypi", "hello", "hello.html")
        ).join();
        final String body = readBody(htmlContent);

        assertTrue(body.startsWith("<!DOCTYPE html>"),
            "HTML index must start with doctype");
        assertTrue(body.contains("hello-0.2.0.whl"),
            "HTML index must link the file");
        assertTrue(body.contains("#sha256="),
            "HTML index must include the sha256 fragment");
    }

    @Test
    void repoIndexWritesBothHtmlAndJson() {
        // Two packages in the repo
        this.storage.save(
            new Key.From("hello", "0.1.0", "hello-0.1.0.whl"),
            new Content.From("h".getBytes())
        ).join();
        this.storage.save(
            new Key.From("world", "0.2.0", "world-0.2.0.whl"),
            new Content.From("w".getBytes())
        ).join();

        new IndexGenerator(
            this.storage,
            Key.ROOT,
            "/pypi"
        ).generateRepoIndex().join();

        assertTrue(
            this.storage.exists(new Key.From(".pypi", "simple.html")).join(),
            "repo-level HTML index must be persisted"
        );
        assertTrue(
            this.storage.exists(new Key.From(".pypi", "simple.json")).join(),
            "repo-level JSON index must be persisted alongside HTML"
        );

        // Repo-level JSON must be a valid PEP 691 response with a
        // projects array carrying both package names.
        final String body = readBody(
            this.storage.value(new Key.From(".pypi", "simple.json")).join()
        );
        final JsonObject parsed = Json.createReader(new StringReader(body)).readObject();
        assertEquals("1.1", parsed.getJsonObject("meta").getString("api-version"));
        assertEquals(2, parsed.getJsonArray("projects").size());
        assertEquals(
            "hello",
            parsed.getJsonArray("projects").getJsonObject(0).getString("name")
        );
        assertEquals(
            "world",
            parsed.getJsonArray("projects").getJsonObject(1).getString("name")
        );
    }

    private static String readBody(final Content content) {
        final java.util.concurrent.CompletableFuture<byte[]> future =
            new java.util.concurrent.CompletableFuture<>();
        content.subscribe(
            new org.reactivestreams.Subscriber<java.nio.ByteBuffer>() {
                private final java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();
                @Override
                public void onSubscribe(final org.reactivestreams.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }
                @Override
                public void onNext(final java.nio.ByteBuffer buf) {
                    final byte[] arr = new byte[buf.remaining()];
                    buf.get(arr);
                    this.out.write(arr, 0, arr.length);
                }
                @Override
                public void onError(final Throwable t) {
                    future.completeExceptionally(t);
                }
                @Override
                public void onComplete() {
                    future.complete(this.out.toByteArray());
                }
            }
        );
        return new String(future.join(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
