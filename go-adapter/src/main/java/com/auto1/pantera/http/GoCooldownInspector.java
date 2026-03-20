/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go cooldown inspector.
 * Inspects go.mod files for dependencies.
 *
 * @since 1.0
 */
final class GoCooldownInspector implements CooldownInspector {

    private static final DateTimeFormatter LAST_MODIFIED = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * Pattern to match require statements in go.mod.
     * Format: require module/path v1.2.3
     */
    private static final Pattern REQUIRE_PATTERN = 
        Pattern.compile("^\\s*require\\s+([^\\s]+)\\s+v?([^\\s]+)", Pattern.MULTILINE);

    /**
     * Pattern to match require blocks in go.mod.
     * Format: require ( ... )
     */
    private static final Pattern REQUIRE_BLOCK_PATTERN = 
        Pattern.compile("require\\s*\\(([^)]+)\\)", Pattern.DOTALL);

    /**
     * Pattern to match individual lines in require block.
     */
    private static final Pattern REQUIRE_LINE_PATTERN = 
        Pattern.compile("^\\s*([^\\s/]+(?:/[^\\s]+)*)\\s+v?([^\\s]+)", Pattern.MULTILINE);

    private final Slice remote;
    private final RepoHead head;

    GoCooldownInspector(final Slice remote) {
        this.remote = remote;
        this.head = new RepoHead(remote);
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        // Try .info file first (contains timestamp), then fall back to .mod file
        final String infoPath = String.format("/%s/@v/v%s.info", artifact, version);
        final String modPath = String.format("/%s/@v/v%s.mod", artifact, version);
        
        return this.head.head(infoPath)
            .thenCompose(headers -> {
                final Optional<Instant> lm = headers.flatMap(GoCooldownInspector::parseLastModified);
                if (lm.isPresent()) {
                    return CompletableFuture.completedFuture(lm);
                }
                // Fallback: try .mod file
                return this.head.head(modPath)
                    .thenApply(modHeaders -> modHeaders.flatMap(GoCooldownInspector::parseLastModified));
            }).toCompletableFuture();
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return this.readGoMod(artifact, version).thenApply(gomod -> {
            if (gomod.isEmpty() || gomod.get().isEmpty()) {
                return Collections.<CooldownDependency>emptyList();
            }
            return parseGoModDependencies(gomod.get());
        }).exceptionally(throwable -> {
            EcsLogger.error("com.auto1.pantera.go")
                .message("Failed to read dependencies")
                .eventCategory("repository")
                .eventAction("cooldown_inspector")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .error(throwable)
                .log();
            return Collections.<CooldownDependency>emptyList();
        });
    }

    private CompletableFuture<Optional<String>> readGoMod(final String artifact, final String version) {
        final String path = String.format("/%s/@v/v%s.mod", artifact, version);
        return this.remote.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(response -> {
            if (!response.status().success()) {
                EcsLogger.warn("com.auto1.pantera.go")
                    .message("Failed to fetch go.mod")
                    .eventCategory("repository")
                    .eventAction("cooldown_inspector")
                    .eventOutcome("failure")
                    .field("url.path", path)
                    .field("http.response.status_code", response.status().code())
                    .log();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return bodyBytes(response.body())
                .thenApply(bytes -> Optional.of(new String(bytes, StandardCharsets.UTF_8)));
        });
    }

    private static Optional<Instant> parseLastModified(final Headers headers) {
        return headers.stream()
            .filter(header -> "Last-Modified".equalsIgnoreCase(header.getKey()))
            .map(Header::getValue)
            .findFirst()
            .flatMap(GoCooldownInspector::parseRfc1123Relaxed);
    }

    private static Optional<Instant> parseRfc1123Relaxed(final String raw) {
        String val = raw == null ? "" : raw.trim();
        if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        val = val.replaceAll("\\s+", " ");
        try {
            return Optional.of(Instant.from(LAST_MODIFIED.parse(val)));
        } catch (final DateTimeParseException ex1) {
            try {
                final DateTimeFormatter relaxed =
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy H:mm:ss z", Locale.US);
                return Optional.of(Instant.from(relaxed.parse(val)));
            } catch (final DateTimeParseException ex2) {
                EcsLogger.warn("com.auto1.pantera.go")
                    .message(String.format("Invalid Last-Modified header: %s", raw))
                    .eventCategory("repository")
                    .eventAction("cooldown_inspector")
                    .eventOutcome("failure")
                    .log();
                return Optional.empty();
            }
        }
    }

    private static CompletableFuture<byte[]> bodyBytes(final org.reactivestreams.Publisher<ByteBuffer> body) {
        return Flowable.fromPublisher(body)
            .reduce(new ByteArrayOutputStream(), (stream, buffer) -> {
                try {
                    stream.write(new Remaining(buffer).bytes());
                    return stream;
                } catch (final java.io.IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
            .map(ByteArrayOutputStream::toByteArray)
            .onErrorReturnItem(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    /**
     * Parse go.mod file for dependencies.
     * Supports both single-line and block require statements.
     *
     * @param gomod Content of go.mod file
     * @return List of dependencies
     */
    private static List<CooldownDependency> parseGoModDependencies(final String gomod) {
        final List<CooldownDependency> result = new ArrayList<>();
        
        // First, remove require blocks from the content to avoid double-parsing
        String contentWithoutBlocks = gomod;
        final Matcher blockMatcher = REQUIRE_BLOCK_PATTERN.matcher(gomod);
        while (blockMatcher.find()) {
            final String block = blockMatcher.group(1);
            final Matcher lineMatcher = REQUIRE_LINE_PATTERN.matcher(block);
            while (lineMatcher.find()) {
                final String module = lineMatcher.group(1);
                final String version = lineMatcher.group(2);
                if (!module.isEmpty() && !version.isEmpty() && !isIndirectInBlock(block, lineMatcher.start())) {
                    result.add(new CooldownDependency(module, version));
                }
            }
            // Remove this block from content to avoid re-parsing
            contentWithoutBlocks = contentWithoutBlocks.replace(blockMatcher.group(0), "");
        }
        
        // Parse single-line require statements (outside of blocks)
        final Matcher singleMatcher = REQUIRE_PATTERN.matcher(contentWithoutBlocks);
        while (singleMatcher.find()) {
            final String module = singleMatcher.group(1);
            final String version = singleMatcher.group(2);
            if (!module.isEmpty() && !version.isEmpty() && !isIndirect(contentWithoutBlocks, singleMatcher.start())) {
                result.add(new CooldownDependency(module, version));
            }
        }
        
        return result;
    }

    /**
     * Check if a require statement is marked as indirect.
     *
     * @param content Full go.mod content
     * @param pos Position of the require statement
     * @return True if indirect
     */
    private static boolean isIndirect(final String content, final int pos) {
        final int lineEnd = content.indexOf('\n', pos);
        if (lineEnd == -1) {
            return content.substring(pos).contains("// indirect");
        }
        return content.substring(pos, lineEnd).contains("// indirect");
    }

    /**
     * Check if a line in a require block is marked as indirect.
     *
     * @param block Require block content
     * @param pos Position in the block
     * @return True if indirect
     */
    private static boolean isIndirectInBlock(final String block, final int pos) {
        final int lineEnd = block.indexOf('\n', pos);
        if (lineEnd == -1) {
            return block.substring(pos).contains("// indirect");
        }
        return block.substring(pos, lineEnd).contains("// indirect");
    }
}
