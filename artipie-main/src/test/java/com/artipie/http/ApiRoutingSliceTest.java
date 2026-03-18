/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test for {@link ApiRoutingSlice}.
 */
class ApiRoutingSliceTest {

    @ParameterizedTest
    @CsvSource({
        // Composer (php) API routes
        "/api/composer/php_repo,/php_repo",
        "/api/composer/php_repo/packages.json,/php_repo/packages.json",
        "/test_prefix/api/composer/php_repo,/test_prefix/php_repo",
        "/test_prefix/api/composer/php_repo/p2/vendor/pkg.json,/test_prefix/php_repo/p2/vendor/pkg.json",
        // Generic API routes with repo_type
        "/api/npm/npm_repo,/npm_repo",
        "/api/pypi/pypi_repo/simple,/pypi_repo/simple",
        "/test_prefix/api/docker/docker_repo,/test_prefix/docker_repo",
        "/prefix/api/helm/helm_repo/index.yaml,/prefix/helm_repo/index.yaml",
        // Generic API routes without repo_type
        "/api/my_repo/some/path,/my_repo/some/path",
        "/test_prefix/api/maven/path/to/artifact,/test_prefix/maven/path/to/artifact",
        // Direct routes (should pass through unchanged)
        "/my_repo,/my_repo",
        "/my_repo/path,/my_repo/path",
        "/test_prefix/my_repo,/test_prefix/my_repo",
        "/test_prefix/my_repo/path,/test_prefix/my_repo/path"
    })
    void shouldRewriteApiPaths(final String input, final String expected) {
        final AtomicReference<String> captured = new AtomicReference<>();
        new ApiRoutingSlice(
            (line, headers, body) -> {
                captured.set(line.uri().getPath());
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            new RequestLine(RqMethod.GET, input),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Path should be rewritten correctly",
            captured.get(),
            Matchers.equalTo(expected)
        );
    }

    @Test
    void shouldPreserveQueryParameters() {
        final AtomicReference<URI> captured = new AtomicReference<>();
        new ApiRoutingSlice(
            (line, headers, body) -> {
                captured.set(line.uri());
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            new RequestLine(RqMethod.GET, "/api/composer/php_repo/packages.json?param=value"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Path should be rewritten",
            captured.get().getPath(),
            Matchers.equalTo("/php_repo/packages.json")
        );
        MatcherAssert.assertThat(
            "Query should be preserved",
            captured.get().getQuery(),
            Matchers.equalTo("param=value")
        );
    }

    @Test
    void shouldHandleRootApiPath() {
        final AtomicReference<String> captured = new AtomicReference<>();
        new ApiRoutingSlice(
            (line, headers, body) -> {
                captured.set(line.uri().getPath());
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            new RequestLine(RqMethod.GET, "/api/npm/my_npm"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Root API path should be rewritten",
            captured.get(),
            Matchers.equalTo("/my_npm")
        );
    }

    @Test
    void shouldPassThroughNonApiPaths() {
        final AtomicReference<String> captured = new AtomicReference<>();
        new ApiRoutingSlice(
            (line, headers, body) -> {
                captured.set(line.uri().getPath());
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            new RequestLine(RqMethod.GET, "/direct/repo/path"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Non-API paths should pass through unchanged",
            captured.get(),
            Matchers.equalTo("/direct/repo/path")
        );
    }

    /**
     * When repo registry is available and segments[1] is NOT a known repo,
     * the first segment should be treated as repo_name, not repo_type.
     * This handles: /api/npm/@scope%2fpkg -> /npm/@scope/pkg
     * and: /api/npm/some-package -> /npm/some-package
     */
    @ParameterizedTest
    @CsvSource({
        // Scoped npm package: npm is repo name, @ayd%2fnpm-proxy-test is path
        "/api/npm/@ayd%2fnpm-proxy-test,/npm/@ayd/npm-proxy-test",
        // Non-scoped package: npm is repo name, some-package is path
        "/api/npm/some-package,/npm/some-package",
        // With prefix
        "/test_prefix/api/npm/@ayd%2fnpm-proxy-test,/test_prefix/npm/@ayd/npm-proxy-test",
        "/test_prefix/api/npm/some-package,/test_prefix/npm/some-package",
        // repo_type + repo_name still works when repo name exists in registry
        "/api/npm/npm/some-package,/npm/some-package",
        "/test_prefix/api/npm/npm/@ayd%2fnpm-proxy-test,/test_prefix/npm/@ayd/npm-proxy-test"
    })
    void shouldDisambiguateWithRepoRegistry(final String input, final String expected) {
        // Registry knows about repo "npm" but NOT "some-package" or "@ayd..."
        final Set<String> knownRepos = Set.of("npm");
        final AtomicReference<String> captured = new AtomicReference<>();
        new ApiRoutingSlice(
            (line, headers, body) -> {
                captured.set(line.uri().getPath());
                return ResponseBuilder.ok().completedFuture();
            },
            knownRepos::contains
        ).response(
            new RequestLine(RqMethod.PUT, input),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "Path should be rewritten correctly with repo registry",
            captured.get(),
            Matchers.equalTo(expected)
        );
    }
}
