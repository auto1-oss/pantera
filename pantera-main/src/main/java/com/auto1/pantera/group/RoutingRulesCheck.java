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
package com.auto1.pantera.group;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * The routing rules of a group configuration, checked before they are
 * saved and tried against a path.
 *
 * <p>A rule that cannot be read makes the whole group unusable when its
 * members are resolved (a pattern that is no regular expression, a rule
 * with neither prefix nor pattern), and one that names a repository that
 * is no member has no effect. Saving such a configuration is refused, with
 * a message that names the rule by its position in the list.</p>
 *
 * <p>A rule names a direct member that is a repository. A group asks the
 * repositories of a nested group itself for a file, but hands a metadata
 * request to the nested group; a rule kept here for a nested group or for
 * one of its repositories would hold for one of the two only. Rules for
 * the repositories of a nested group are kept in that group: they come
 * along into every group that contains it.</p>
 *
 * <p>The rules follow {@link RoutingRule}: a prefix is compared with the
 * start of the path, a pattern must match the whole path, both without the
 * leading slash; a member with several rules is asked when one matches; a
 * member without rules is asked for every path.</p>
 *
 * @since 2.2.15
 */
public final class RoutingRulesCheck {

    /**
     * Key of the routing rules.
     */
    private static final String ROUTING = "routing";

    /**
     * Key of the members, and of a rule's member.
     */
    private static final String MEMBERS = "members";

    /**
     * The repository configuration (the {@code repo} object).
     */
    private final JsonObject repo;

    /**
     * Configuration of another repository by name, to look into nested
     * groups.
     */
    private final Function<String, Optional<JsonObject>> lookup;

    /**
     * Ctor.
     *
     * @param repo The repository configuration (the {@code repo} object)
     * @param lookup Configuration of another repository by name
     */
    public RoutingRulesCheck(
        final JsonObject repo, final Function<String, Optional<JsonObject>> lookup
    ) {
        this.repo = repo;
        this.lookup = lookup;
    }

    /**
     * What is wrong with the rules.
     *
     * @return One line per problem, each naming its rule; empty when the
     *  rules can be saved
     */
    public List<String> problems() {
        final List<String> problems = new ArrayList<>(2);
        final JsonValue routing = this.repo.get(RoutingRulesCheck.ROUTING);
        if (!this.group() || routing == null || routing.getValueType() == JsonValue.ValueType.NULL) {
            return problems;
        }
        if (routing.getValueType() != JsonValue.ValueType.ARRAY) {
            problems.add("routing is a list of rules");
            return problems;
        }
        final Tree tree = this.tree();
        final Map<String, Integer> seen = new HashMap<>();
        final JsonArray rules = routing.asJsonArray();
        for (int idx = 0; idx < rules.size(); idx += 1) {
            RoutingRulesCheck.problem(rules.get(idx), tree, seen, idx + 1)
                .ifPresent(problems::add);
        }
        return problems;
    }

    /**
     * The rules of the nested groups. They limit the repositories of
     * those groups in this group too and are changed in their own group.
     *
     * @return Rules that can be read, each with the group that holds it
     */
    public List<Inherited> inherited() {
        return this.tree().inherited();
    }

    /**
     * Try a path against the rules: which members would the group ask.
     * Call only for rules without {@link #problems()}.
     *
     * @param path Path inside the group, with or without a leading slash
     * @return The direct members in their order, each asked or skipped
     */
    public List<Asked> walk(final String path) {
        final List<Parsed> own = this.parsed();
        final Tree tree = this.tree();
        final List<Asked> walk = new ArrayList<>(8);
        for (final Leaf leaf : tree.leaves()) {
            walk.add(RoutingRulesCheck.decide(leaf, path, own, tree.inherited()));
        }
        return walk;
    }

    private static Asked decide(
        final Leaf leaf, final String path, final List<Parsed> own,
        final List<Inherited> inherited
    ) {
        boolean ruled = false;
        int matched = 0;
        for (final Parsed rule : own) {
            if (rule.rule().member().equals(leaf.member())) {
                ruled = true;
                if (matched == 0 && rule.rule().matches(path)) {
                    matched = rule.number();
                }
            }
        }
        String holder = "";
        String passed = "";
        for (final Inherited rule : inherited) {
            if (rule.member().equals(leaf.member())) {
                if (holder.isEmpty()) {
                    holder = rule.group();
                }
                if (passed.isEmpty() && rule.rule().matches(path)) {
                    passed = rule.group();
                }
            }
        }
        final Asked res;
        if (!ruled && holder.isEmpty()) {
            res = new Asked(
                leaf.member(), leaf.through(), true, 0, "no rules: asked for every path"
            );
        } else if (matched > 0) {
            res = new Asked(
                leaf.member(), leaf.through(), true, matched, "rule " + matched + " matches"
            );
        } else if (!passed.isEmpty()) {
            res = new Asked(
                leaf.member(), leaf.through(), true, 0,
                "a rule of the group '" + passed + "' matches"
            );
        } else if (ruled) {
            res = new Asked(leaf.member(), leaf.through(), false, 0, "no rule matches: skipped");
        } else {
            res = new Asked(
                leaf.member(), leaf.through(), false, 0,
                "no rule of the group '" + holder + "' matches: skipped"
            );
        }
        return res;
    }

    /**
     * Number of rules in the configuration.
     *
     * @return Count, 0 when there are none or they are no list
     */
    public int count() {
        final JsonValue routing = this.repo.get(RoutingRulesCheck.ROUTING);
        int count = 0;
        if (routing != null && routing.getValueType() == JsonValue.ValueType.ARRAY) {
            count = routing.asJsonArray().size();
        }
        return count;
    }

    private boolean group() {
        return RoutingRulesCheck.isGroup(this.repo);
    }

    private List<Parsed> parsed() {
        final List<Parsed> rules = new ArrayList<>(8);
        final JsonValue routing = this.repo.get(RoutingRulesCheck.ROUTING);
        if (routing != null && routing.getValueType() == JsonValue.ValueType.ARRAY) {
            final JsonArray list = routing.asJsonArray();
            for (int idx = 0; idx < list.size(); idx += 1) {
                final JsonObject rule = list.getJsonObject(idx);
                final String member = rule.getString("member");
                final String prefix = RoutingRulesCheck.text(rule.get("prefix"));
                if (prefix.isEmpty()) {
                    rules.add(
                        new Parsed(
                            idx + 1,
                            new RoutingRule.PathPattern(
                                member, RoutingRulesCheck.text(rule.get("pattern"))
                            )
                        )
                    );
                } else {
                    rules.add(new Parsed(idx + 1, new RoutingRule.PathPrefix(member, prefix)));
                }
            }
        }
        return rules;
    }

    /**
     * The group as it is walked: the repositories of nested groups in the
     * place of those groups, each once, in the order they are asked (the
     * order of {@link GroupMemberFlattener}).
     *
     * @return The repositories, the group each nested name belongs to and
     *  the rules of the nested groups
     */
    private Tree tree() {
        final Tree tree = new Tree(
            new ArrayList<>(8), new HashMap<>(), new ArrayList<>(2),
            new HashSet<>(RoutingRulesCheck.strings(this.repo.get(RoutingRulesCheck.MEMBERS))),
            new HashSet<>(2)
        );
        this.descend(this.repo, "", tree, new HashSet<>(4), new HashSet<>(16));
        return tree;
    }

    private void descend(
        final JsonObject group, final String name, final Tree tree,
        final Set<String> groups, final Set<String> listed
    ) {
        for (final String member : RoutingRulesCheck.strings(group.get(RoutingRulesCheck.MEMBERS))) {
            if (!name.isEmpty()) {
                tree.owners().putIfAbsent(member, name);
            }
            final Optional<JsonObject> nested =
                this.lookup.apply(member).filter(RoutingRulesCheck::isGroup);
            if (nested.isEmpty()) {
                if (listed.add(member)) {
                    tree.leaves().add(new Leaf(member, name));
                }
            } else {
                if (name.isEmpty()) {
                    tree.nested().add(member);
                }
                if (groups.add(member)) {
                    tree.inherited().addAll(RoutingRulesCheck.rulesOf(member, nested.get()));
                    this.descend(nested.get(), member, tree, groups, listed);
                }
            }
        }
    }

    private static List<Inherited> rulesOf(final String group, final JsonObject repo) {
        final List<Inherited> rules = new ArrayList<>(2);
        final JsonValue routing = repo.get(RoutingRulesCheck.ROUTING);
        if (routing != null && routing.getValueType() == JsonValue.ValueType.ARRAY) {
            for (final JsonValue value : routing.asJsonArray()) {
                if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                    continue;
                }
                final JsonObject rule = value.asJsonObject();
                final String member = RoutingRulesCheck.text(rule.get("member"));
                final String prefix = RoutingRulesCheck.text(rule.get("prefix"));
                final String pattern = RoutingRulesCheck.text(rule.get("pattern"));
                final boolean readable = !member.isEmpty()
                    && (!prefix.isEmpty()
                    || !pattern.isEmpty() && RoutingRulesCheck.broken(pattern).isEmpty());
                if (readable) {
                    rules.add(new Inherited(group, member, prefix, pattern));
                }
            }
        }
        return rules;
    }

    private static boolean isGroup(final JsonObject repo) {
        return repo.get("type") instanceof JsonString str && str.getString().endsWith("-group");
    }

    private static Optional<String> problem(
        final JsonValue value, final Tree tree, final Map<String, Integer> seen,
        final int number
    ) {
        final String label = "rule " + number + ": ";
        String problem = null;
        if (value.getValueType() != JsonValue.ValueType.OBJECT) {
            problem = "a rule is an object with a member and a prefix or a pattern";
        } else {
            final JsonObject rule = value.asJsonObject();
            final String member = RoutingRulesCheck.text(rule.get("member"));
            final String prefix = RoutingRulesCheck.text(rule.get("prefix"));
            final String pattern = RoutingRulesCheck.text(rule.get("pattern"));
            if (member.isEmpty()) {
                problem = "a rule names a member";
            } else if (prefix.isEmpty() && pattern.isEmpty()) {
                problem = "a rule needs a prefix or a pattern";
            } else if (!prefix.isEmpty() && !pattern.isEmpty()) {
                problem = "a rule has a prefix or a pattern, not both";
            } else if (tree.misplaced(member).isPresent()) {
                problem = tree.misplaced(member).get();
            } else if (!pattern.isEmpty() && RoutingRulesCheck.broken(pattern).isPresent()) {
                problem = "pattern is not a valid regular expression: "
                    + RoutingRulesCheck.broken(pattern).get();
            } else {
                final Integer first = seen.putIfAbsent(
                    member + '\u0000' + prefix + '\u0000' + pattern, number
                );
                if (first != null) {
                    problem = "the same rule as rule " + first;
                }
            }
        }
        return Optional.ofNullable(problem).map(text -> label + text);
    }

    private static Optional<String> broken(final String pattern) {
        Optional<String> why = Optional.empty();
        try {
            Pattern.compile(pattern);
        } catch (final PatternSyntaxException err) {
            why = Optional.of(err.getDescription());
        }
        return why;
    }

    private static String text(final JsonValue value) {
        final String res;
        if (value instanceof JsonString str) {
            res = str.getString();
        } else {
            res = "";
        }
        return res;
    }

    private static List<String> strings(final JsonValue value) {
        final List<String> res = new ArrayList<>(8);
        if (value != null && value.getValueType() == JsonValue.ValueType.ARRAY) {
            for (final JsonValue item : value.asJsonArray()) {
                if (item instanceof JsonString str) {
                    res.add(str.getString());
                }
            }
        }
        return res;
    }

    /**
     * A repository in the walk for one path.
     *
     * @param member Repository name
     * @param through The nested group it is a member of, empty for a
     *  direct member
     * @param asked Whether the group asks it for the path
     * @param rule Number of the rule of this group that matched, 0 for none
     * @param why The decision in words
     * @since 2.2.15
     */
    public record Asked(String member, String through, boolean asked, int rule, String why) {
    }

    /**
     * A rule of a nested group.
     *
     * @param group The nested group that holds the rule
     * @param member The repository it limits
     * @param prefix Path prefix, empty for a pattern rule
     * @param pattern Regular expression, empty for a prefix rule
     * @since 2.2.15
     */
    public record Inherited(String group, String member, String prefix, String pattern) {

        /**
         * The rule.
         *
         * @return Rule to match a path with
         */
        RoutingRule rule() {
            final RoutingRule rule;
            if (this.prefix.isEmpty()) {
                rule = new RoutingRule.PathPattern(this.member, this.pattern);
            } else {
                rule = new RoutingRule.PathPrefix(this.member, this.prefix);
            }
            return rule;
        }
    }

    /**
     * A repository the group asks.
     *
     * @param member Repository name
     * @param through The nested group it comes from, empty for a direct
     *  member
     * @since 2.2.15
     */
    private record Leaf(String member, String through) {
    }

    /**
     * The group with its nested groups resolved.
     *
     * @param leaves The repositories in the order they are asked
     * @param owners For a name found in a nested group, that group
     * @param inherited Rules of the nested groups
     * @param direct Names of the direct members
     * @param nested Direct members that are groups
     * @since 2.2.15
     */
    private record Tree(
        List<Leaf> leaves, Map<String, String> owners, List<Inherited> inherited,
        Set<String> direct, Set<String> nested
    ) {

        /**
         * Why a rule of this group cannot name a repository.
         *
         * @param member Name in the rule
         * @return The problem, empty for a direct member that is a
         *  repository
         */
        Optional<String> misplaced(final String member) {
            final Optional<String> res;
            if (this.nested.contains(member)) {
                res = Optional.of(
                    String.format(
                        "'%s' is a group: give its repositories their rules in '%s'",
                        member, member
                    )
                );
            } else if (this.direct.contains(member)) {
                res = Optional.empty();
            } else if (this.owners.containsKey(member)) {
                res = Optional.of(
                    String.format(
                        "'%s' is a member of the group '%s': give it its rules there",
                        member, this.owners.get(member)
                    )
                );
            } else {
                res = Optional.of("'" + member + "' is not a member of the group");
            }
            return res;
        }
    }

    /**
     * A rule with its position in the list.
     *
     * @param number Position, from 1
     * @param rule The rule
     * @since 2.2.15
     */
    private record Parsed(int number, RoutingRule rule) {
    }
}
