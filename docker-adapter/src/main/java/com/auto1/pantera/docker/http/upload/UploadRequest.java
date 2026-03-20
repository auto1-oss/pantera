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
package com.auto1.pantera.docker.http.upload;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.RqByRegex;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqParams;

import java.util.Optional;
import java.util.regex.Matcher;

/**
 * @param name   Image repository name.
 * @param uuid   Upload uuid.
 * @param params Request parameters.
 */
public record UploadRequest(String name, String uuid, RqParams params) {

    public static UploadRequest from(RequestLine line) {
        Matcher matcher = new RqByRegex(line, PathPatterns.UPLOADS).path();
        return new UploadRequest(
            ImageRepositoryName.validate(matcher.group("name")),
            matcher.group("uuid"),
            new RqParams(line.uri())
        );
    }

    public Digest digest() {
        return params.value("digest")
            .map(Digest.FromString::new)
            .orElseThrow(() -> new IllegalStateException("Request parameter `digest` is not exist"));
    }

    public Optional<Digest> mount() {
        return params.value("mount").map(Digest.FromString::new);
    }

    public Optional<String> from() {
        return params.value("from").map(ImageRepositoryName::validate);
    }

}
