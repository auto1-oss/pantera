/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.metadata;

/**
 * Nuspec xml metadata field.
 * @since 0.6
 */
public interface NuspecField {

    /**
     * Original raw value (as it was in xml).
     * @return String value
     */
    String raw();

    /**
     * Normalized value of the field.
     * @return Normalized value
     */
    String normalized();

}
