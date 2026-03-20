/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.jetty.http3;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.Promise;

/**
 * HTTP/3 response sender using Jetty 12.1.4 Stream API.
 * Sends Artipie Response through HTTP/3 stream.
 * @since 0.31
 */
public final class Http3Connection {

    /**
     * HTTP/3 server stream.
     */
    private final Stream.Server stream;

    /**
     * Ctor.
     * @param stream HTTP/3 server stream
     */
    public Http3Connection(final Stream.Server stream) {
        this.stream = stream;
    }

    /**
     * Send Artipie Response through HTTP/3 stream.
     * @param response Artipie response to send
     * @return CompletableFuture that completes when response is sent
     */
    public CompletableFuture<Void> send(final Response response) {
        final int statusCode = response.status().code();
        final MetaData.Response metadata = new MetaData.Response(
            statusCode,
            HttpStatus.getMessage(statusCode),
            HttpVersion.HTTP_3,
            HttpFields.from(
                StreamSupport.stream(response.headers().spliterator(), false)
                    .map(item -> new HttpField(item.getKey(), item.getValue()))
                    .toArray(HttpField[]::new)
            )
        );
        
        final CompletableFuture<Void> future = new CompletableFuture<>();
        
        // Send headers with Promise callback
        this.stream.respond(
            new HeadersFrame(metadata, false),
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Stream stream) {
                    // Headers sent successfully, now send body
                    Http3Connection.this.sendBody(response.body(), future);
                }
                
                @Override
                public void failed(Throwable error) {
                    // Failed to send headers
                    future.completeExceptionally(error);
                }
            }
        );
        
        return future;
    }

    /**
     * Send response body through HTTP/3 stream.
     * @param body Response body content
     * @param future Future to complete when done
     */
    private void sendBody(final Content body, final CompletableFuture<Void> future) {
        Flowable.fromPublisher(body)
            .doOnComplete(
                () -> {
                    // Send final empty frame to signal end
                    this.stream.data(
                        new DataFrame(ByteBuffer.wrap(new byte[]{}), true),
                        new Promise.Invocable.NonBlocking<>() {
                            @Override
                            public void succeeded(Stream stream) {
                                future.complete(null);
                            }
                            
                            @Override
                            public void failed(Throwable error) {
                                future.completeExceptionally(error);
                            }
                        }
                    );
                }
            )
            .doOnError(future::completeExceptionally)
            .forEach(
                buffer -> {
                    // Send data frame (not last)
                    this.stream.data(
                        new DataFrame(buffer, false),
                        new Promise.Invocable.NonBlocking<>() {
                            @Override
                            public void succeeded(Stream stream) {
                                // Continue sending
                            }
                            
                            @Override
                            public void failed(Throwable error) {
                                future.completeExceptionally(error);
                            }
                        }
                    );
                }
            );
    }
}
