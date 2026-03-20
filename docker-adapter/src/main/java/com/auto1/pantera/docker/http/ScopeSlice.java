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

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.security.Permission;

/**
 * Slice requiring authorization.
 */
public interface ScopeSlice extends Slice {

    Permission permission(RequestLine line);

}
