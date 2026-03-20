/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http.metadata;

import com.auto1.pantera.nuget.PackageIdentity;
import java.net.URL;

/**
 * Package content location.
 *
 * @since 0.1
 */
public interface ContentLocation {

    /**
     * Get URL for package content.
     *
     * @param identity Package identity.
     * @return URL for package content.
     */
    URL url(PackageIdentity identity);
}
