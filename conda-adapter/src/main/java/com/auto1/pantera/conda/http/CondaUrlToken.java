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
package com.auto1.pantera.conda.http;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The token of a conda {@code /t/<token>/} URL segment.
 *
 * <p>The conda CLI keeps only {@code [A-Za-z0-9-]} of a {@code /t/} token and
 * splices the rest of the segment back into the URL, so a Pantera token (a
 * JWT, which contains {@code .} and {@code _}) is given to it hex-encoded.
 * A segment that is the hex encoding of a JWT is decoded; any other segment
 * is the token as is.</p>
 *
 * @since 2.2.9
 */
final class CondaUrlToken {

    /**
     * Shape of a JWT: three base64url parts.
     */
    private static final Pattern JWT = Pattern.compile(
        "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"
    );

    /**
     * Even-length hex.
     */
    private static final Pattern HEX = Pattern.compile("^(?:[0-9a-fA-F]{2})+$");

    /**
     * URL segment.
     */
    private final String segment;

    /**
     * Ctor.
     * @param segment URL segment following {@code /t/}
     */
    CondaUrlToken(final String segment) {
        this.segment = segment;
    }

    /**
     * The token the segment carries.
     * @return Decoded JWT for a hex-encoded JWT, else the segment itself
     */
    String value() {
        String res = this.segment;
        if (CondaUrlToken.HEX.matcher(this.segment).matches()) {
            final String decoded = new String(
                HexFormat.of().parseHex(this.segment), StandardCharsets.US_ASCII
            );
            if (CondaUrlToken.JWT.matcher(decoded).matches()) {
                res = decoded;
            }
        }
        return res;
    }

    /**
     * Whether the segment carries a JWT, raw or hex-encoded.
     * @return True for a JWT
     */
    boolean jwt() {
        return CondaUrlToken.JWT.matcher(this.value()).matches();
    }
}
