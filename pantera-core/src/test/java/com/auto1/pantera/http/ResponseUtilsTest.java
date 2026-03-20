/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ResponseUtils}.
 */
class ResponseUtilsTest {

    @Test
    void consumeAndDiscardReturnsNull() throws Exception {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final CompletableFuture<Void> result = ResponseUtils.consumeAndDiscard(response);
        
        assertThat("Should return null after consuming", result.get(), is(nullValue()));
    }

    @Test
    void consumeAndReturn404Returns404() throws Exception {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final Response result = ResponseUtils.consumeAndReturn404(response).get();
        
        assertThat("Should return 404", result.status().code(), is(404));
    }

    @Test
    void consumeAndReturnReturnsReplacement() throws Exception {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final Response replacement = ResponseBuilder.accepted().build();
        final Response result = ResponseUtils.consumeAndReturn(response, replacement).get();
        
        assertThat("Should return replacement", result.status().code(), is(202));
    }

    @Test
    void consumeAndFailThrowsException() {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final RuntimeException exception = new RuntimeException("test error");
        final CompletableFuture<Void> result = ResponseUtils.consumeAndFail(response, exception);
        
        final ExecutionException thrown = assertThrows(
            ExecutionException.class,
            result::get,
            "Should throw exception after consuming"
        );
        
        assertThat("Should be our exception", thrown.getCause(), is(exception));
    }

    @Test
    void isSuccessOrConsumeReturnsTrueForSuccess() throws Exception {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final Boolean result = ResponseUtils.isSuccessOrConsume(response).get();
        
        assertThat("Should return true for success", result, is(true));
    }

    @Test
    void isSuccessOrConsumeReturnsFalseForError() throws Exception {
        final Response response = ResponseBuilder.notFound()
            .body(new Content.From("not found".getBytes()))
            .build();
        
        final Boolean result = ResponseUtils.isSuccessOrConsume(response).get();
        
        assertThat("Should return false for error", result, is(false));
    }

    @Test
    void consumeIfConsumesWhenTrue() throws Exception {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final Response result = ResponseUtils.consumeIf(response, true).get();
        
        assertThat("Should return null when consumed", result, is(nullValue()));
    }

    @Test
    void consumeIfPassesThroughWhenFalse() throws Exception {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From("test body".getBytes()))
            .build();
        
        final Response result = ResponseUtils.consumeIf(response, false).get();
        
        assertThat("Should return same response", result, is(response));
    }
}
