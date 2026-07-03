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

import java.util.function.Function;

/**
 * Discriminated union of a successful value or a {@link Fault}.
 *
 * <p>The slice contract is {@code CompletionStage<Result<Response>>} — exceptions
 * inside a slice body that escape to {@code .exceptionally(...)} are only
 * converted to {@link Fault.Internal}; they are never the primary fault-signaling
 * mechanism. See §3.2 of {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * @param <T> Success value type.
 * @since 2.2.0
 */
public sealed interface Result<T> {

    /**
     * Factory for a successful result.
     *
     * @param value Non-null success value.
     * @param <T>   Success type.
     * @return {@link Ok} wrapping {@code value}.
     */
    static <T> Result<T> ok(final T value) {
        return new Ok<>(value);
    }

    /**
     * Factory for a failed result.
     *
     * @param fault Non-null fault.
     * @param <T>   Success type of the (never-produced) value.
     * @return {@link Err} wrapping {@code fault}.
     */
    static <T> Result<T> err(final Fault fault) {
        return new Err<>(fault);
    }

    /**
     * Map the success value, short-circuiting on {@link Err}.
     *
     * @param fn  Mapping function. Must not throw.
     * @param <R> New success type.
     * @return A new {@link Result} with the mapped value, or the original {@link Err}.
     */
    default <R> Result<R> map(final Function<? super T, ? extends R> fn) {
        return switch (this) {
            case Ok<T> ok -> Result.ok(fn.apply(ok.value()));
            case Err<T> err -> Result.err(err.fault());
        };
    }

    /**
     * Chain another Result-producing computation, short-circuiting on {@link Err}.
     *
     * @param fn  Mapping function that returns another {@link Result}.
     * @param <R> New success type.
     * @return The mapped Result, or the original {@link Err} unchanged.
     */
    default <R> Result<R> flatMap(final Function<? super T, ? extends Result<R>> fn) {
        return switch (this) {
            case Ok<T> ok -> fn.apply(ok.value());
            case Err<T> err -> Result.err(err.fault());
        };
    }

    /**
     * Successful result.
     *
     * @param value Success value.
     * @param <T>   Success type.
     */
    record Ok<T>(T value) implements Result<T> {
    }

    /**
     * Failed result carrying a {@link Fault}.
     *
     * @param fault Fault description.
     * @param <T>   Success type of the (never-produced) value.
     */
    record Err<T>(Fault fault) implements Result<T> {
    }
}
