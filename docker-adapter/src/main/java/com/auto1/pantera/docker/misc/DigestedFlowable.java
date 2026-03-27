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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.docker.Digest;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.codec.binary.Hex;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * {@link Flowable} that calculates digest of origin {@link Publisher} bytes when they pass by.
 */
public final class DigestedFlowable extends Flowable<ByteBuffer> {

    /**
     * Origin publisher.
     */
    private final Publisher<ByteBuffer> origin;

    /**
     * Calculated digest.
     */
    private final AtomicReference<Digest> dig;

    /**
     * @param origin Origin publisher.
     */
    public DigestedFlowable(final Publisher<ByteBuffer> origin) {
        this.dig = new AtomicReference<>();
        this.origin = origin;
    }

    @Override
    public void subscribeActual(final Subscriber<? super ByteBuffer> subscriber) {
        final MessageDigest sha = Digests.SHA256.get();
        Flowable.fromPublisher(this.origin).map(
            buf -> {
                sha.update(new Remaining(buf, true).bytes());
                return buf;
            }
        ).doOnComplete(
            () -> this.dig.set(
                new Digest.Sha256(Hex.encodeHexString(sha.digest()))
            )
        ).subscribe(subscriber);
    }

    /**
     * Calculated digest.
     *
     * @return Digest.
     */
    public Digest digest() {
        return Objects.requireNonNull(this.dig.get(), "Digest is not yet calculated.");
    }
}
