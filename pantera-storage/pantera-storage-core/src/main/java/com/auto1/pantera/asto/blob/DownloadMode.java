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
package com.auto1.pantera.asto.blob;

import java.util.Locale;

/**
 * Per-repo byte-serving strategy for redirect-eligible immutable byte objects
 * (WS1.7, spec {@code WS1-storage-for-scale.md} &sect;3.B2). Never consulted
 * for metadata routes (packument, {@code maven-metadata.xml}, PyPI simple
 * index, {@code /v2/} manifests+tags, Composer {@code /p2/}, Go {@code
 * @v/list}/{@code @latest}) -- those are never wired to check this at all,
 * regardless of value; eligibility is an opt-in decision made by the ROUTE
 * (see {@code Blob#presignedUrl} in {@code docker-adapter} for the first
 * wired case), never a blanket rule keyed off this enum.
 *
 * @since 2.3.0
 */
public enum DownloadMode {

    /**
     * Always stream bytes through Pantera -- never attempt a presigned
     * redirect. Safe default: byte-identical to pre-2.3.0 behaviour. The
     * correct choice for locked-down/air-gapped repos whose clients cannot
     * reach the object store directly.
     */
    STREAM,

    /**
     * Attempt a presigned redirect for every eligible byte GET where it is
     * technically possible right now (the object is durably confirmed in
     * the blob store AND the backend has presign configured); fall back to
     * {@link #STREAM} otherwise. An operator choosing this mode is
     * declaring "my clients can reach the object store directly".
     */
    REDIRECT,

    /**
     * Same technical eligibility gate as {@link #REDIRECT} (durably present
     * + presign configured); intended for repos where the operator has not
     * made an explicit network-topology declaration one way or the other.
     * Falls back to {@link #STREAM} under the identical conditions {@link
     * #REDIRECT} does -- the two modes differ only in operator intent, not
     * in mechanism, as of WS1.7.
     */
    AUTO;

    /**
     * Parses a configured {@code download-mode} value. Unrecognized or
     * absent values default to {@link #STREAM} -- the safe, behaviour-
     * preserving choice -- rather than failing repo config load.
     *
     * @param raw Configured value ({@code "redirect"}/{@code "stream"}/
     *  {@code "auto"}, case-insensitive), or {@code null}.
     * @return Parsed mode, defaulting to {@link #STREAM}.
     */
    public static DownloadMode from(final String raw) {
        final DownloadMode mode;
        if (raw == null) {
            mode = STREAM;
        } else {
            mode = switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "redirect" -> REDIRECT;
                case "auto" -> AUTO;
                default -> STREAM;
            };
        }
        return mode;
    }
}
