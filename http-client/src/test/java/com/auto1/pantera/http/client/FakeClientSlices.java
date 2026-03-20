/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client;

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fake {@link ClientSlices} implementation that returns specified result
 * and captures last method call.
 *
 * @since 0.3
 */
public final class FakeClientSlices implements ClientSlices {

    /**
     * Captured scheme. True - secure HTTPS protocol, false - insecure HTTP.
     */
    private final AtomicReference<Boolean> csecure;

    /**
     * Captured host.
     */
    private final AtomicReference<String> chost;

    /**
     * Captured port.
     */
    private final AtomicReference<Integer> cport;

    /**
     * Slice returned by requests.
     */
    private final Slice result;


    public FakeClientSlices(Response response) {
        this((line, headers, body)-> CompletableFuture.completedFuture(response));
    }

    /**
     * @param result Slice returned by requests.
     */
    public FakeClientSlices(final Slice result) {
        this.result = result;
        this.csecure = new AtomicReference<>();
        this.chost = new AtomicReference<>();
        this.cport = new AtomicReference<>();
    }

    /**
     * Get captured scheme.
     *
     * @return Scheme.
     */
    public Boolean capturedSecure() {
        return this.csecure.get();
    }

    /**
     * Get captured host.
     *
     * @return Host.
     */
    public String capturedHost() {
        return this.chost.get();
    }

    /**
     * Get captured port.
     *
     * @return Port.
     */
    public Integer capturedPort() {
        return this.cport.get();
    }

    @Override
    public Slice http(final String host) {
        this.csecure.set(false);
        this.chost.set(host);
        this.cport.set(null);
        return this.result;
    }

    @Override
    public Slice http(final String host, final int port) {
        this.csecure.set(false);
        this.chost.set(host);
        this.cport.set(port);
        return this.result;
    }

    @Override
    public Slice https(final String host) {
        this.csecure.set(true);
        this.chost.set(host);
        this.cport.set(null);
        return this.result;
    }

    @Override
    public Slice https(final String host, final int port) {
        this.csecure.set(true);
        this.chost.set(host);
        this.cport.set(port);
        return this.result;
    }
}
