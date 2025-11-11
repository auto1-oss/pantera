/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.jcabi.log.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Flattens nested group repository structures at configuration load time.
 * 
 * <p>Problem: Nested groups cause exponential request explosion
 * <pre>
 * group_all → [group_maven, group_npm, local]
 *   group_maven → [maven_central, maven_apache, maven_local]
 *   group_npm → [npm_registry, npm_local]
 * 
 * Runtime queries: 6 repositories with complex depth tracking
 * </pre>
 * 
 * <p>Solution: Flatten at config load time
 * <pre>
 * group_all → [maven_central, maven_apache, maven_local, 
 *              npm_registry, npm_local, local]
 * 
 * Runtime queries: 6 repositories in parallel, no depth tracking
 * </pre>
 * 
 * <p>Benefits:
 * <ul>
 *   <li>No runtime recursion</li>
 *   <li>No exponential explosion</li>
 *   <li>Automatic deduplication</li>
 *   <li>Cycle detection</li>
 *   <li>Preserves priority order</li>
 * </ul>
 * 
 * @since 1.18.23
 */
public final class GroupMemberFlattener {

    /**
     * Function to check if a repository name refers to a group.
     * Returns true if repo type ends with "-group".
     */
    private final Function<String, Boolean> isGroup;

    /**
     * Function to get members of a group repository.
     * Returns empty list for non-group repositories.
     */
    private final Function<String, List<String>> getMembers;

    /**
     * Constructor with custom repo type checker and member resolver.
     * 
     * @param isGroup Function to check if repo is a group
     * @param getMembers Function to get group members
     */
    public GroupMemberFlattener(
        final Function<String, Boolean> isGroup,
        final Function<String, List<String>> getMembers
    ) {
        this.isGroup = isGroup;
        this.getMembers = getMembers;
    }

    /**
     * Flatten group members recursively.
     * 
     * <p>Algorithm:
     * <ol>
     *   <li>Start with group's direct members</li>
     *   <li>For each member:
     *     <ul>
     *       <li>If leaf repository → add to result</li>
     *       <li>If group → recursively flatten and add members</li>
     *     </ul>
     *   </li>
     *   <li>Deduplicate while preserving order (LinkedHashSet)</li>
     *   <li>Detect cycles (throws if found)</li>
     * </ol>
     * 
     * @param groupName Group repository name to flatten
     * @return Flat list of leaf repository names (no nested groups)
     * @throws IllegalStateException if circular dependency detected
     */
    public List<String> flatten(final String groupName) {
        final Set<String> visited = new HashSet<>();
        final List<String> flat = flattenRecursive(groupName, visited);
        
        // Deduplicate while preserving order
        final List<String> deduplicated = new ArrayList<>(new LinkedHashSet<>(flat));
        
        Logger.info(
            this,
            "Flattened group %s: %d members → %d unique members",
            groupName,
            flat.size(),
            deduplicated.size()
        );
        
        return deduplicated;
    }

    /**
     * Recursive flattening with cycle detection.
     * 
     * @param repoName Repository to flatten (group or leaf)
     * @param visited Already visited groups (for cycle detection)
     * @return Flat list of leaf repositories
     * @throws IllegalStateException if cycle detected
     */
    private List<String> flattenRecursive(
        final String repoName,
        final Set<String> visited
    ) {
        // Check for cycles
        if (visited.contains(repoName)) {
            throw new IllegalStateException(
                String.format(
                    "Circular group dependency detected: %s (visited: %s)",
                    repoName,
                    visited
                )
            );
        }
        
        final List<String> result = new ArrayList<>();
        
        // Check if this is a group repository
        if (this.isGroup.apply(repoName)) {
            Logger.debug(
                this,
                "Flattening group: %s (depth=%d)",
                repoName,
                visited.size()
            );
            
            // Mark as visited
            visited.add(repoName);
            
            // Get members and recursively flatten each
            final List<String> members = this.getMembers.apply(repoName);
            for (String member : members) {
                result.addAll(flattenRecursive(member, new HashSet<>(visited)));
            }
            
            // Unmark (for parallel branches)
            visited.remove(repoName);
        } else {
            // Leaf repository - add directly
            Logger.debug(
                this,
                "Adding leaf repository: %s",
                repoName
            );
            result.add(repoName);
        }
        
        return result;
    }

    /**
     * Flatten and deduplicate in one pass.
     * More efficient for large group hierarchies.
     * 
     * @param groupName Group repository name
     * @return Deduplicated flat list
     */
    public List<String> flattenAndDeduplicate(final String groupName) {
        final Set<String> visited = new HashSet<>();
        final LinkedHashSet<String> unique = new LinkedHashSet<>();
        flattenIntoSet(groupName, visited, unique);
        
        Logger.info(
            this,
            "Flattened group %s: %d unique members",
            groupName,
            unique.size()
        );
        
        return new ArrayList<>(unique);
    }

    /**
     * Flatten directly into a set for deduplication.
     * 
     * @param repoName Repository to flatten
     * @param visited Visited groups (cycle detection)
     * @param unique Result set (preserves order)
     */
    private void flattenIntoSet(
        final String repoName,
        final Set<String> visited,
        final LinkedHashSet<String> unique
    ) {
        if (visited.contains(repoName)) {
            throw new IllegalStateException(
                String.format("Circular dependency: %s", repoName)
            );
        }
        
        if (this.isGroup.apply(repoName)) {
            visited.add(repoName);
            for (String member : this.getMembers.apply(repoName)) {
                flattenIntoSet(member, new HashSet<>(visited), unique);
            }
            visited.remove(repoName);
        } else {
            unique.add(repoName);
        }
    }

    /**
     * Validate group structure without flattening.
     * Checks for cycles and missing members.
     * 
     * @param groupName Group to validate
     * @return Validation errors (empty if valid)
     */
    public List<String> validate(final String groupName) {
        final List<String> errors = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        
        try {
            validateRecursive(groupName, visited, errors);
        } catch (Exception e) {
            errors.add("Validation failed: " + e.getMessage());
        }
        
        return errors;
    }

    /**
     * Recursive validation.
     * 
     * @param repoName Repository to validate
     * @param visited Visited groups
     * @param errors Accumulated errors
     */
    private void validateRecursive(
        final String repoName,
        final Set<String> visited,
        final List<String> errors
    ) {
        if (visited.contains(repoName)) {
            errors.add("Circular dependency: " + repoName);
            return;
        }
        
        if (this.isGroup.apply(repoName)) {
            visited.add(repoName);
            final List<String> members = this.getMembers.apply(repoName);
            
            if (members.isEmpty()) {
                errors.add("Empty group: " + repoName);
            }
            
            for (String member : members) {
                try {
                    validateRecursive(member, new HashSet<>(visited), errors);
                } catch (Exception e) {
                    errors.add("Error validating " + member + ": " + e.getMessage());
                }
            }
            
            visited.remove(repoName);
        }
    }
}
