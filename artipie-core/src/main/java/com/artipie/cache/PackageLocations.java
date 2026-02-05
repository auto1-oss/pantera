/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which group members have a specific package.
 * Thread-safe container for member location statuses.
 *
 * @since 1.18.0
 */
public final class PackageLocations {

    /**
     * Status of package location at a group member.
     */
    public enum LocationStatus {
        /**
         * Package exists at this member.
         */
        EXISTS,

        /**
         * Package does not exist at this member (negative cache).
         */
        NOT_EXISTS,

        /**
         * Status unknown - needs to be checked.
         */
        UNKNOWN
    }

    /**
     * Entry recording status and expiration time.
     *
     * @param status Location status
     * @param expiresAt When this entry expires
     */
    public record LocationEntry(LocationStatus status, Instant expiresAt) {

        /**
         * Check if this entry has expired.
         *
         * @return True if expired
         */
        public boolean isExpired() {
            // Handle Instant.MAX specially to avoid overflow
            if (this.expiresAt.equals(Instant.MAX)) {
                return false;
            }
            return Instant.now().isAfter(this.expiresAt);
        }
    }

    /**
     * Member location entries. Thread-safe map.
     */
    private final Map<String, LocationEntry> members;

    /**
     * Create empty package locations.
     */
    public PackageLocations() {
        this.members = new ConcurrentHashMap<>();
    }

    /**
     * Get list of members where package EXISTS and is not expired.
     *
     * @return List of member names with valid EXISTS status
     */
    public List<String> knownLocations() {
        final List<String> result = new ArrayList<>();
        for (final var entry : this.members.entrySet()) {
            final LocationEntry value = entry.getValue();
            if (value.status() == LocationStatus.EXISTS
                && !value.isExpired()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Get list of members with UNKNOWN status or expired entries.
     *
     * @return List of member names needing status check
     */
    public List<String> unknownMembers() {
        final List<String> result = new ArrayList<>();
        for (final var entry : this.members.entrySet()) {
            final LocationEntry value = entry.getValue();
            if (value.status() == LocationStatus.UNKNOWN
                || value.isExpired()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Check if member is negatively cached (NOT_EXISTS and not expired).
     *
     * @param member Member name
     * @return True if negatively cached
     */
    public boolean isNegativelyCached(final String member) {
        final LocationEntry entry = this.members.get(member);
        if (entry == null) {
            return false;
        }
        return entry.status() == LocationStatus.NOT_EXISTS
            && !entry.isExpired();
    }

    /**
     * Get status for a member. Returns UNKNOWN if missing or expired.
     *
     * @param member Member name
     * @return Location status
     */
    public LocationStatus getStatus(final String member) {
        final LocationEntry entry = this.members.get(member);
        if (entry == null || entry.isExpired()) {
            return LocationStatus.UNKNOWN;
        }
        return entry.status();
    }

    /**
     * Set status for a member with expiration time.
     *
     * @param member Member name
     * @param status Location status
     * @param expiresAt When this entry expires
     */
    public void setStatus(
        final String member,
        final LocationStatus status,
        final Instant expiresAt
    ) {
        this.members.put(member, new LocationEntry(status, expiresAt));
    }

    /**
     * Remove a specific member entry.
     *
     * @param member Member name to remove
     */
    public void remove(final String member) {
        this.members.remove(member);
    }

    /**
     * Clear all member entries.
     */
    public void clear() {
        this.members.clear();
    }

    /**
     * Get copy of all entries for serialization.
     *
     * @return Map copy of member entries
     */
    public Map<String, LocationEntry> entries() {
        return new HashMap<>(this.members);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final PackageLocations other = (PackageLocations) obj;
        return Objects.equals(this.members, other.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.members);
    }

    @Override
    public String toString() {
        return String.format("PackageLocations{members=%s}", this.members);
    }
}
