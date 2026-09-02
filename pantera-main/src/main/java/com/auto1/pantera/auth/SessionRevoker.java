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
package com.auto1.pantera.auth;

import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.log.EcsLogger;

/**
 * "A credential changed — drop every live token" primitive shared by the
 * password change, admin password reset and user-disable paths.
 *
 * <p>Before 2.2.9 only {@code disableUser} revoked tokens: a password change
 * or reset flushed caches but left every existing session (access token),
 * refresh token and API token authorizing until natural expiry, so
 * rotating a compromised password did not evict the attacker
 * (SecOps token-revocation #38). Revocation is cluster-wide: the blocklist
 * entry fans out over Valkey pub/sub and the DB rows are shared.</p>
 *
 * @since 2.2.9
 */
public final class SessionRevoker {

    /**
     * Blocklist window for the user-wide access-token revocation: covers the
     * default refresh-token TTL; anything older is already expired by
     * its own {@code exp}.
     */
    private static final int REVOKE_WINDOW_SECONDS = 7 * 24 * 3600;

    /**
     * Access-token blocklist; {@code null} when not wired (no Valkey / no DB).
     */
    private final RevocationBlocklist blocklist;

    /**
     * Persisted refresh/API token store; {@code null} in no-DB boots.
     */
    private final UserTokenDao tokenDao;

    /**
     * Ctor.
     *
     * @param blocklist Access-token blocklist, may be {@code null}
     * @param tokenDao Token DAO, may be {@code null}
     */
    public SessionRevoker(final RevocationBlocklist blocklist, final UserTokenDao tokenDao) {
        this.blocklist = blocklist;
        this.tokenDao = tokenDao;
    }

    /**
     * Revoke every live token for the user: access tokens via the
     * user-wide blocklist, refresh and API tokens via the DB.
     *
     * @param username Subject whose tokens are revoked
     * @return Number of persisted (refresh/API) tokens revoked
     */
    public int revokeAll(final String username) {
        if (this.blocklist != null) {
            this.blocklist.revokeUser(username, REVOKE_WINDOW_SECONDS);
        }
        int revoked = 0;
        if (this.tokenDao != null) {
            revoked = this.tokenDao.revokeAllForUser(username);
        }
        EcsLogger.info("com.auto1.pantera.auth")
            .message("Credential change: revoked live sessions and " + revoked + " persisted token(s)")
            .eventCategory("authentication")
            .eventAction("session_revoke")
            .eventOutcome("success")
            .field("user.name", username)
            .field("log.source", "application")
            .log();
        return revoked;
    }
}
