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
package com.auto1.pantera.http.fault;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ValueNotFoundException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Round-trip tests for {@link FaultClassifier#classify(Throwable, String)}.
 */
final class FaultClassifierTest {

    private static final String WHERE = "unit-test";

    @Test
    void timeoutExceptionClassifiesAsDeadline() {
        final Fault fault = FaultClassifier.classify(new TimeoutException("slow"), WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Deadline.class));
        final Fault.Deadline deadline = (Fault.Deadline) fault;
        MatcherAssert.assertThat(
            "deadline label propagated", deadline.where(), Matchers.is(WHERE)
        );
        MatcherAssert.assertThat(
            "unknown budget is ZERO",
            deadline.budget(), Matchers.is(Duration.ZERO)
        );
    }

    @Test
    void connectExceptionClassifiesAsInternal() {
        final ConnectException ce = new ConnectException("refused");
        final Fault fault = FaultClassifier.classify(ce, WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Internal.class));
        final Fault.Internal internal = (Fault.Internal) fault;
        MatcherAssert.assertThat(
            "cause preserved", internal.cause(), Matchers.sameInstance(ce)
        );
        MatcherAssert.assertThat(
            "where propagated", internal.where(), Matchers.is(WHERE)
        );
    }

    @Test
    void ioExceptionClassifiesAsInternal() {
        final IOException ioe = new IOException("broken pipe");
        final Fault fault = FaultClassifier.classify(ioe, WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Internal.class));
        MatcherAssert.assertThat(
            ((Fault.Internal) fault).cause(), Matchers.sameInstance(ioe)
        );
    }

    @Test
    void valueNotFoundClassifiesAsStorageUnavailable() {
        final ValueNotFoundException vnf = new ValueNotFoundException(new Key.From("missing"));
        final Fault fault = FaultClassifier.classify(vnf, WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.StorageUnavailable.class));
        final Fault.StorageUnavailable su = (Fault.StorageUnavailable) fault;
        MatcherAssert.assertThat(
            "cause preserved", su.cause(), Matchers.sameInstance(vnf)
        );
        MatcherAssert.assertThat(
            "exception message propagated",
            su.key(), Matchers.is(vnf.getMessage())
        );
    }

    @Test
    void queueFullIllegalStateClassifiesAsOverload() {
        final Fault fault = FaultClassifier.classify(
            new IllegalStateException("Queue full"), WHERE
        );
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Overload.class));
        final Fault.Overload ov = (Fault.Overload) fault;
        MatcherAssert.assertThat(
            "resource label", ov.resource(), Matchers.is("event-queue")
        );
        MatcherAssert.assertThat(
            "retry-after hint", ov.retryAfter(), Matchers.is(Duration.ofSeconds(1))
        );
    }

    @Test
    void otherIllegalStateExceptionFallsBackToInternal() {
        final IllegalStateException ise = new IllegalStateException("not queue full");
        final Fault fault = FaultClassifier.classify(ise, WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Internal.class));
        MatcherAssert.assertThat(
            ((Fault.Internal) fault).cause(), Matchers.sameInstance(ise)
        );
    }

    @Test
    void defaultClassifiesAsInternal() {
        final RuntimeException rte = new RuntimeException("unknown");
        final Fault fault = FaultClassifier.classify(rte, WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Internal.class));
        MatcherAssert.assertThat(
            ((Fault.Internal) fault).cause(), Matchers.sameInstance(rte)
        );
    }

    @Test
    void completionExceptionIsUnwrappedBeforeClassification() {
        final TimeoutException inner = new TimeoutException("inner");
        final CompletionException wrapper = new CompletionException(inner);
        final Fault fault = FaultClassifier.classify(wrapper, WHERE);
        MatcherAssert.assertThat(
            "CompletionException unwrapped — saw TimeoutException",
            fault, Matchers.instanceOf(Fault.Deadline.class)
        );
    }

    @Test
    void nestedCompletionExceptionsAreFullyUnwrapped() {
        final ConnectException root = new ConnectException("denied");
        final CompletionException middle = new CompletionException(root);
        final CompletionException outer = new CompletionException(middle);
        final Fault fault = FaultClassifier.classify(outer, WHERE);
        MatcherAssert.assertThat(fault, Matchers.instanceOf(Fault.Internal.class));
        MatcherAssert.assertThat(
            ((Fault.Internal) fault).cause(), Matchers.sameInstance(root)
        );
    }

    @Test
    void completionExceptionWithNullCauseIsClassifiedDirectly() {
        final CompletionException bare = new CompletionException("no cause", null);
        final Fault fault = FaultClassifier.classify(bare, WHERE);
        MatcherAssert.assertThat(
            "bare CompletionException falls to default Internal",
            fault, Matchers.instanceOf(Fault.Internal.class)
        );
    }

    @Test
    void selfReferencingCompletionExceptionDoesNotLoop() {
        // Defensive check: if a pathological Throwable reports itself as its own
        // cause, unwrap must terminate (otherwise classify() would spin forever).
        final CompletionException selfRef = new SelfReferencingCompletionException();
        final Fault fault = FaultClassifier.classify(selfRef, WHERE);
        MatcherAssert.assertThat(
            "self-referencing cause is treated as terminal",
            fault, Matchers.instanceOf(Fault.Internal.class)
        );
        MatcherAssert.assertThat(
            ((Fault.Internal) fault).cause(), Matchers.sameInstance(selfRef)
        );
    }

    /** Pathological throwable whose getCause() returns itself. */
    private static final class SelfReferencingCompletionException extends CompletionException {
        private static final long serialVersionUID = 1L;

        SelfReferencingCompletionException() {
            super("self");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
