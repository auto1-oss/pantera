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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.SliceSimple;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link GoCooldownInspector}.
 *
 * @since 1.0
 */
class GoCooldownInspectorTest {

    @Test
    void parsesGoModDependencies() throws Exception {
        final String gomod = """
            module example.com/mymodule
            
            go 1.21
            
            require (
                github.com/pkg/errors v0.9.1
                golang.org/x/sync v0.3.0
                example.com/other v1.2.3 // indirect
            )
            
            require github.com/stretchr/testify v1.8.4
            """;
        
        final Slice remote = new SliceSimple(
            ResponseBuilder.ok()
                .header("Last-Modified", "Mon, 01 Jan 2024 12:00:00 GMT")
                .body(gomod.getBytes(StandardCharsets.UTF_8))
                .build()
        );
        
        final GoCooldownInspector inspector = new GoCooldownInspector(remote);
        final List<CooldownDependency> deps = inspector.dependencies("example.com/mymodule", "1.0.0").get();
        
        // Should have 3 dependencies (excluding indirect)
        assertEquals(3, deps.size());
        assertTrue(deps.stream().anyMatch(d -> d.artifact().equals("github.com/pkg/errors")));
        assertTrue(deps.stream().anyMatch(d -> d.artifact().equals("golang.org/x/sync")));
        assertTrue(deps.stream().anyMatch(d -> d.artifact().equals("github.com/stretchr/testify")));
    }

    @Test
    void parsesReleaseDateFromLastModified() throws Exception {
        final Slice remote = new SliceSimple(
            ResponseBuilder.ok()
                .header("Last-Modified", "Mon, 01 Jan 2024 12:00:00 GMT")
                .body("module test".getBytes(StandardCharsets.UTF_8))
                .build()
        );
        
        final GoCooldownInspector inspector = new GoCooldownInspector(remote);
        final Instant date = inspector.releaseDate("example.com/test", "1.0.0").get().orElseThrow();
        
        assertNotNull(date);
    }

    /**
     * Regression: GoLatestHandler and GoListHandler pass versions with the
     * Go-canonical "v" prefix ("v1.2.3") while the artifact-path regex in
     * CachedProxySlice strips it ("1.2.3"). Before the fix, the inspector
     * format-string {@code /%s/@v/v%s.info} produced
     * {@code /.../@v/vv1.2.3.info} for the prefixed case, upstream returned
     * 404, release date came back empty, and cooldown failed open — which
     * silently bypassed the @latest rewrite for every module (observed in
     * v2.2.0 against github.com/openai/openai-go/v3, blocked-until 2026-04-30
     * leaked a 403 on .info instead of being rewritten to a prior version).
     *
     * <p>Pin: prefixed and unprefixed inputs must both resolve to the same
     * upstream path {@code /<module>/@v/v1.2.3.info}.</p>
     */
    @Test
    void normalisesLeadingVInVersion() throws Exception {
        final Queue<String> requestedPaths = new ConcurrentLinkedQueue<>();
        final Slice remote = (line, headers, body) -> {
            requestedPaths.add(line.uri().getPath());
            if (line.uri().getPath().equals("/example.com/foo/@v/v1.2.3.info")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Last-Modified", "Mon, 01 Jan 2024 12:00:00 GMT")
                        .body(new byte[0])
                        .build()
                );
            }
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };
        final GoCooldownInspector inspector = new GoCooldownInspector(remote);

        final Optional<Instant> prefixed =
            inspector.releaseDate("example.com/foo", "v1.2.3").get();
        final Optional<Instant> bare =
            inspector.releaseDate("example.com/foo", "1.2.3").get();

        assertTrue(
            prefixed.isPresent(),
            "v-prefixed version must resolve to the same upstream path as the bare form; "
                + "requested paths: " + requestedPaths
        );
        assertEquals(prefixed, bare, "prefixed and bare inputs must produce the same Instant");
        assertTrue(
            requestedPaths.stream().noneMatch(p -> p.contains("/vv")),
            "inspector must never produce a double-v path like /@v/vv1.2.3.info; "
                + "requested paths: " + requestedPaths
        );
    }

    @Test
    void handlesEmptyGoMod() throws Exception {
        final Slice remote = new SliceSimple(ResponseBuilder.notFound().build());
        
        final GoCooldownInspector inspector = new GoCooldownInspector(remote);
        final List<CooldownDependency> deps = inspector.dependencies("example.com/missing", "1.0.0").get();
        
        assertTrue(deps.isEmpty());
    }
}
