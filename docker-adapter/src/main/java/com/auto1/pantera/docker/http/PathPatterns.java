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
package com.auto1.pantera.docker.http;

import java.util.regex.Pattern;

public interface PathPatterns {
    Pattern BASE = Pattern.compile("^/v2/$");
    Pattern MANIFESTS = Pattern.compile("^/v2/(?<name>.*)/manifests/(?<reference>.*)$");
    Pattern TAGS = Pattern.compile("^/v2/(?<name>.*)/tags/list$");
    Pattern BLOBS = Pattern.compile("^/v2/(?<name>.*)/blobs/(?<digest>(?!(uploads/)).*)$");
    Pattern UPLOADS = Pattern.compile("^/v2/(?<name>.*)/blobs/uploads/(?<uuid>[^/]*).*$");
    Pattern CATALOG = Pattern.compile("^/v2/_catalog$");
    Pattern REFERRERS = Pattern.compile("^/v2/(?<name>.*)/referrers/(?<digest>.*)$");
}
