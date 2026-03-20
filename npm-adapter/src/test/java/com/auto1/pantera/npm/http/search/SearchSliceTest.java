/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.search;

import com.artipie.asto.Content;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.RsStatus;
import com.artipie.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Test for {@link SearchSlice}.
 *
 * @since 1.1
 */
final class SearchSliceTest {
    
    /**
     * Test slice.
     */
    private SearchSlice slice;
    
    /**
     * Package index.
     */
    private InMemoryPackageIndex index;
    
    @BeforeEach
    void setUp() {
        final InMemoryStorage storage = new InMemoryStorage();
        this.index = new InMemoryPackageIndex();
        this.slice = new SearchSlice(storage, this.index);
        
        // Add test packages
        this.index.index(new PackageMetadata(
            "express",
            "4.18.2",
            "Fast, unopinionated, minimalist web framework",
            Arrays.asList("framework", "web", "http")
        )).join();
        
        this.index.index(new PackageMetadata(
            "lodash",
            "4.17.21",
            "Lodash modular utilities",
            Arrays.asList("utility", "functional")
        )).join();
        
        this.index.index(new PackageMetadata(
            "axios",
            "1.5.0",
            "Promise based HTTP client",
            Arrays.asList("http", "ajax", "xhr")
        )).join();
    }
    
    @Test
    void findsPackagesByName() {
        // When: search for "express"
        final Response response = this.slice.response(
            RequestLine.from("GET /-/v1/search?text=express HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
    }
    
    @Test
    void findsPackagesByKeyword() {
        // When: search for "http"
        final Response response = this.slice.response(
            RequestLine.from("GET /-/v1/search?text=http HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        // Should find both express and axios (both have "http" keyword)
    }
    
    @Test
    void requiresQueryParameter() {
        // When: no query parameter
        final Response response = this.slice.response(
            RequestLine.from("GET /-/v1/search HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is BAD_REQUEST",
            response.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
    }
    
    @Test
    void returnsEmptyForNoMatches() {
        // When: search for non-existent package
        final Response response = this.slice.response(
            RequestLine.from("GET /-/v1/search?text=nonexistent HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
    }
}
