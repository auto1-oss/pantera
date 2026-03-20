/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.auto1.pantera.nuget;

import com.auto1.pantera.nuget.metadata.Nuspec;

/**
 * NuGet package.
 *
 * @since 0.1
 */
public interface NuGetPackage {

    /**
     * Extract package description in .nuspec format.
     *
     * @return Package description.
     */
    Nuspec nuspec();
}
