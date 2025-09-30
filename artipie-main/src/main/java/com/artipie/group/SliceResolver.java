/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Key;
import com.artipie.http.Slice;

/**
 * Resolver of slices by repository name and port.
 */
@FunctionalInterface
public interface SliceResolver {
    Slice slice(Key name, int port);
}

