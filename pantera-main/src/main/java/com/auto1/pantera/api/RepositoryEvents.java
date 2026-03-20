/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

/**
 * Repository events communicated over Vert.x event bus to support
 * dynamic repository lifecycle without restart.
 */
public final class RepositoryEvents {
    private RepositoryEvents() { }

    public static final String ADDRESS = "pantera.repos.events";

    public static final String UPSERT = "UPSERT";
    public static final String REMOVE = "REMOVE";
    public static final String MOVE = "MOVE";

    public static String upsert(final String name) {
        return String.join("|", UPSERT, name);
    }

    public static String remove(final String name) {
        return String.join("|", REMOVE, name);
    }

    public static String move(final String oldname, final String newname) {
        return String.join("|", MOVE, oldname, newname);
    }
}

