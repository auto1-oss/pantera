/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.RepositorySlices;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.settings.PrefixesConfig;
import com.artipie.settings.repo.Repositories;
import com.artipie.test.TestSettings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SliceByPath} with prefix support.
 */
class SliceByPathPrefixTest {

    @Test
    void routesUnprefixedPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2"));
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        final ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices).slice(keyCaptor.capture(), anyInt());
        assertEquals("test", keyCaptor.getValue().string());
    }

    @Test
    void stripsPrefixFromPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2"));
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        slice.response(
            new RequestLine(RqMethod.GET, "/p1/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        final ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices).slice(keyCaptor.capture(), anyInt());
        assertEquals("test", keyCaptor.getValue().string());
    }

    @Test
    void stripsMultiplePrefixes() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2", "migration"));
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        // Test p1 prefix
        slice.response(
            new RequestLine(RqMethod.GET, "/p1/maven/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices).slice(keyCaptor.capture(), anyInt());
        assertEquals("maven", keyCaptor.getValue().string());

        // Test p2 prefix
        final RepositorySlices slices2 = mockSlices();
        final SliceByPath slice2 = new SliceByPath(slices2, prefixes);
        slice2.response(
            new RequestLine(RqMethod.GET, "/p2/npm/package.tgz"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices2).slice(keyCaptor.capture(), anyInt());
        assertEquals("npm", keyCaptor.getValue().string());

        // Test migration prefix
        final RepositorySlices slices3 = mockSlices();
        final SliceByPath slice3 = new SliceByPath(slices3, prefixes);
        slice3.response(
            new RequestLine(RqMethod.GET, "/migration/docker/image"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices3).slice(keyCaptor.capture(), anyInt());
        assertEquals("docker", keyCaptor.getValue().string());
    }

    @Test
    void doesNotStripUnknownPrefix() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1", "p2"));
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        slice.response(
            new RequestLine(RqMethod.GET, "/unknown/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        final ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices).slice(keyCaptor.capture(), anyInt());
        assertEquals("unknown", keyCaptor.getValue().string());
    }

    @Test
    void handlesEmptyPrefixList() {
        final PrefixesConfig prefixes = new PrefixesConfig();
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        final ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(slices).slice(keyCaptor.capture(), anyInt());
        assertEquals("test", keyCaptor.getValue().string());
    }

    @Test
    void supportsAllHttpMethods() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        
        for (final RqMethod method : Arrays.asList(
            RqMethod.GET, RqMethod.HEAD, RqMethod.PUT, RqMethod.POST, RqMethod.DELETE
        )) {
            final RepositorySlices slices = mockSlices();
            final SliceByPath slice = new SliceByPath(slices, prefixes);

            slice.response(
                new RequestLine(method, "/p1/test/artifact.jar"),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

            final ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
            verify(slices).slice(keyCaptor.capture(), anyInt());
            assertEquals("test", keyCaptor.getValue().string());
        }
    }

    @Test
    void handlesRootPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(404, response.status().code());
    }

    @Test
    void handlesPrefixOnlyPath() {
        final PrefixesConfig prefixes = new PrefixesConfig(Arrays.asList("p1"));
        final RepositorySlices slices = mockSlices();
        final SliceByPath slice = new SliceByPath(slices, prefixes);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/p1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Should result in empty repo name after stripping
        assertEquals(404, response.status().code());
    }

    private RepositorySlices mockSlices() {
        final RepositorySlices slices = mock(RepositorySlices.class);
        final Slice repoSlice = mock(Slice.class);
        when(repoSlice.response(any(), any(), any())).thenReturn(
            CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        );
        when(slices.slice(any(Key.class), anyInt())).thenReturn(repoSlice);
        return slices;
    }
}
