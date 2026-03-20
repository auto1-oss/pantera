/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

/**
 * Reasons for triggering a cooldown block.
 */
public enum CooldownReason {
    /**
     * Requested version is newer than the currently cached version.
     */
    NEWER_THAN_CACHE,
    /**
     * Requested version was released recently and has never been cached before.
     */
    FRESH_RELEASE
}
