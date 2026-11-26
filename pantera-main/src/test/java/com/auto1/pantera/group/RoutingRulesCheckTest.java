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

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link RoutingRulesCheck}: the routing rules of a group are
 * checked before they are saved, and a path can be tried against them.
 */
final class RoutingRulesCheckTest {

    @Test
    void validRulesHaveNoProblem() {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"hosted\",\"central\",\"jboss\"],"
                    + "\"routing\":["
                    + "{\"member\":\"central\",\"pattern\":\"(?!com/example/).*\"},"
                    + "{\"member\":\"jboss\",\"prefix\":\"org/jboss/\"},"
                    + "{\"member\":\"jboss\",\"prefix\":\"/org/wildfly/\"}]}"
            ),
            new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"maven-group\",\"members\":[\"a\"]}",
        "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":null}",
        "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":[]}",
        "{\"type\":\"maven\",\"storage\":{},\"routing\":[{\"member\":\"x\"}]}"
    })
    void groupWithoutRulesAndRepositoryThatIsNoGroupHaveNoProblem(final String repo) {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(repo), new IsEqual<>(List.of())
        );
    }

    @Test
    void patternThatIsNoRegularExpressionIsNamedWithItsRule() {
        final List<String> problems = RoutingRulesCheckTest.problems(
            "{\"type\":\"maven-group\",\"members\":[\"a\",\"b\"],\"routing\":["
                + "{\"member\":\"a\",\"prefix\":\"org/\"},"
                + "{\"member\":\"b\",\"pattern\":\"(?!unclosed\"}]}"
        );
        MatcherAssert.assertThat("one problem", problems.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "it names the second rule", problems.get(0), new StringContains("rule 2")
        );
        MatcherAssert.assertThat(
            "and says what is wrong", problems.get(0),
            new StringContains("not a valid regular expression")
        );
    }

    @Test
    void ruleForARepositoryThatIsNoMemberIsRefused() {
        final List<String> problems = RoutingRulesCheckTest.problems(
            "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":["
                + "{\"member\":\"gone\",\"prefix\":\"org/\"}]}"
        );
        MatcherAssert.assertThat(
            problems, new IsEqual<>(List.of("rule 1: 'gone' is not a member of the group"))
        );
    }

    @Test
    void ruleForARepositoryOfANestedGroupBelongsInThatGroup() {
        // The group asks a nested group's repositories itself for a file,
        // but hands a metadata request to the nested group: a rule kept
        // here would hold for one and not for the other.
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(
                    "{\"type\":\"maven-group\",\"members\":[\"inner\"],\"routing\":["
                        + "{\"member\":\"leaf\",\"prefix\":\"a/\"},"
                        + "{\"member\":\"deep-leaf\",\"prefix\":\"b/\"}]}"
                ),
                RoutingRulesCheckTest.NESTED
            ).problems(),
            new IsEqual<>(
                List.of(
                    "rule 1: 'leaf' is a member of the group 'inner': give it its rules there",
                    "rule 2: 'deep-leaf' is a member of the group 'deeper': give it its rules there"
                )
            )
        );
    }

    @Test
    void ruleForANestedGroupIsRefused() {
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(
                    "{\"type\":\"maven-group\",\"members\":[\"inner\"],\"routing\":["
                        + "{\"member\":\"inner\",\"prefix\":\"c/\"}]}"
                ),
                RoutingRulesCheckTest.NESTED
            ).problems(),
            new IsEqual<>(
                List.of(
                    "rule 1: 'inner' is a group: give its repositories their rules in 'inner'"
                )
            )
        );
    }

    @Test
    void repositoryThatIsADirectMemberAndInANestedGroupTakesItsRuleHere() {
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(
                    "{\"type\":\"maven-group\",\"members\":[\"leaf\",\"inner\"],\"routing\":["
                        + "{\"member\":\"leaf\",\"prefix\":\"a/\"}]}"
                ),
                RoutingRulesCheckTest.NESTED
            ).problems(),
            new IsEqual<>(List.of())
        );
    }

    @Test
    void walkListsTheRepositoriesOfNestedGroupsInTheOrderTheyAreAsked() {
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(
                    "{\"type\":\"maven-group\",\"members\":[\"hosted\",\"inner\",\"leaf\"]}"
                ),
                RoutingRulesCheckTest.NESTED
            ).walk("a/b.jar"),
            new IsEqual<>(
                List.of(
                    new RoutingRulesCheck.Asked(
                        "hosted", "", true, 0, "no rules: asked for every path"
                    ),
                    new RoutingRulesCheck.Asked(
                        "leaf", "inner", true, 0, "no rules: asked for every path"
                    ),
                    new RoutingRulesCheck.Asked(
                        "deep-leaf", "deeper", true, 0, "no rules: asked for every path"
                    )
                )
            )
        );
    }

    @Test
    void walkAppliesTheRulesOfANestedGroupToItsRepositories() {
        final RoutingRulesCheck check = new RoutingRulesCheck(
            RoutingRulesCheckTest.json(
                "{\"type\":\"maven-group\",\"members\":[\"hosted\",\"ruled\"]}"
            ),
            RoutingRulesCheckTest.NESTED
        );
        MatcherAssert.assertThat(
            "a path the nested group's rule does not match",
            check.walk("com/acme/a.jar"),
            new IsEqual<>(
                List.of(
                    new RoutingRulesCheck.Asked(
                        "hosted", "", true, 0, "no rules: asked for every path"
                    ),
                    new RoutingRulesCheck.Asked(
                        "central", "ruled", false, 0,
                        "no rule of the group 'ruled' matches: skipped"
                    )
                )
            )
        );
        MatcherAssert.assertThat(
            "a path it matches",
            check.walk("/org/a.jar").get(1),
            new IsEqual<>(
                new RoutingRulesCheck.Asked(
                    "central", "ruled", true, 0, "a rule of the group 'ruled' matches"
                )
            )
        );
    }

    @Test
    void rulesOfNestedGroupsAreListedWithTheirGroup() {
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(
                    "{\"type\":\"maven-group\",\"members\":[\"hosted\",\"ruled\"],"
                        + "\"routing\":[{\"member\":\"hosted\",\"prefix\":\"com/\"}]}"
                ),
                RoutingRulesCheckTest.NESTED
            ).inherited(),
            new IsEqual<>(
                List.of(new RoutingRulesCheck.Inherited("ruled", "central", "org/", ""))
            )
        );
    }

    @Test
    void walkOfAGroupThatContainsItselfEnds() {
        final Map<String, String> known = Map.of(
            "inner", "{\"type\":\"maven-group\",\"members\":[\"outer\",\"inner\",\"leaf\"]}",
            "outer", "{\"type\":\"maven-group\",\"members\":[\"inner\"]}"
        );
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(known.get("outer")),
                name -> Optional.ofNullable(known.get(name)).map(RoutingRulesCheckTest::json)
            ).walk("a").stream().map(RoutingRulesCheck.Asked::member).toList(),
            new IsEqual<>(List.of("leaf"))
        );
    }

    @Test
    void groupThatContainsItselfDoesNotLoopTheCheck() {
        final Map<String, String> known = Map.of(
            "inner", "{\"type\":\"maven-group\",\"members\":[\"outer\",\"inner\"]}",
            "outer", "{\"type\":\"maven-group\",\"members\":[\"inner\"]}"
        );
        MatcherAssert.assertThat(
            new RoutingRulesCheck(
                RoutingRulesCheckTest.json(
                    "{\"type\":\"maven-group\",\"members\":[\"inner\"],\"routing\":["
                        + "{\"member\":\"nobody\",\"prefix\":\"a/\"}]}"
                ),
                name -> Optional.ofNullable(known.get(name)).map(RoutingRulesCheckTest::json)
            ).problems().size(),
            new IsEqual<>(1)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"member\":\"a\"}",
        "{\"member\":\"a\",\"prefix\":\"\"}",
        "{\"member\":\"a\",\"pattern\":\"\"}",
        "{\"member\":\"a\",\"prefix\":\"\",\"pattern\":\"\"}"
    })
    void ruleWithoutPrefixAndPatternIsRefused(final String rule) {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":[" + rule + "]}"
            ),
            new IsEqual<>(List.of("rule 1: a rule needs a prefix or a pattern"))
        );
    }

    @Test
    void ruleWithPrefixAndPatternIsRefused() {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":["
                    + "{\"member\":\"a\",\"prefix\":\"org/\",\"pattern\":\"org/.*\"}]}"
            ),
            new IsEqual<>(List.of("rule 1: a rule has a prefix or a pattern, not both"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"prefix\":\"org/\"}",
        "{\"member\":\"\",\"prefix\":\"org/\"}",
        "{\"member\":7,\"prefix\":\"org/\"}"
    })
    void ruleWithoutMemberIsRefused(final String rule) {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":[" + rule + "]}"
            ),
            new IsEqual<>(List.of("rule 1: a rule names a member"))
        );
    }

    @Test
    void sameRuleTwiceIsRefused() {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":["
                    + "{\"member\":\"a\",\"prefix\":\"org/\"},"
                    + "{\"member\":\"a\",\"pattern\":\"org/.*\"},"
                    + "{\"member\":\"a\",\"prefix\":\"org/\"}]}"
            ),
            new IsEqual<>(List.of("rule 3: the same rule as rule 1"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":\"org/\"}",
        "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":{\"member\":\"a\"}}"
    })
    void routingThatIsNoListIsRefused(final String repo) {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(repo),
            new IsEqual<>(List.of("routing is a list of rules"))
        );
    }

    @Test
    void ruleThatIsNoObjectIsRefused() {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":[\"org/\"]}"
            ),
            new IsEqual<>(List.of("rule 1: a rule is an object with a member and a prefix or a pattern"))
        );
    }

    @Test
    void everyProblemIsReported() {
        MatcherAssert.assertThat(
            RoutingRulesCheckTest.problems(
                "{\"type\":\"maven-group\",\"members\":[\"a\"],\"routing\":["
                    + "{\"member\":\"x\",\"prefix\":\"org/\"},"
                    + "{\"member\":\"a\",\"pattern\":\"[\"}]}"
            ).size(),
            new IsEqual<>(2)
        );
    }

    @Test
    void pathIsTriedAgainstTheRulesMemberByMember() {
        final List<RoutingRulesCheck.Asked> walk = new RoutingRulesCheck(
            RoutingRulesCheckTest.json(
                "{\"type\":\"maven-group\",\"members\":[\"hosted\",\"central\",\"jboss\"],"
                    + "\"routing\":["
                    + "{\"member\":\"central\",\"pattern\":\"(?!com/example/).*\"},"
                    + "{\"member\":\"jboss\",\"prefix\":\"org/jboss/\"},"
                    + "{\"member\":\"jboss\",\"prefix\":\"org/wildfly/\"}]}"
            ),
            name -> Optional.empty()
        ).walk("/org/wildfly/core/1.0/core-1.0.pom");
        MatcherAssert.assertThat(
            "members in their order, each asked or skipped, with the rule that decided",
            walk,
            new IsEqual<>(
                List.of(
                    new RoutingRulesCheck.Asked("hosted", "", true, 0, "no rules: asked for every path"),
                    new RoutingRulesCheck.Asked("central", "", true, 1, "rule 1 matches"),
                    new RoutingRulesCheck.Asked("jboss", "", true, 3, "rule 3 matches")
                )
            )
        );
    }

    @Test
    void memberWhoseRulesDoNotMatchIsSkipped() {
        final List<RoutingRulesCheck.Asked> walk = new RoutingRulesCheck(
            RoutingRulesCheckTest.json(
                "{\"type\":\"maven-group\",\"members\":[\"hosted\",\"central\",\"jboss\"],"
                    + "\"routing\":["
                    + "{\"member\":\"central\",\"pattern\":\"(?!com/example/).*\"},"
                    + "{\"member\":\"jboss\",\"prefix\":\"org/jboss/\"}]}"
            ),
            name -> Optional.empty()
        ).walk("com/example/app/1.0/app-1.0.jar");
        MatcherAssert.assertThat(
            walk,
            new IsEqual<>(
                List.of(
                    new RoutingRulesCheck.Asked("hosted", "", true, 0, "no rules: asked for every path"),
                    new RoutingRulesCheck.Asked("central", "", false, 0, "no rule matches: skipped"),
                    new RoutingRulesCheck.Asked("jboss", "", false, 0, "no rule matches: skipped")
                )
            )
        );
    }

    /**
     * Repositories known to the checks of nested groups: {@code inner}
     * holds {@code leaf} and the group {@code deeper}, {@code ruled} limits
     * its {@code central} to {@code org/}.
     */
    private static final java.util.function.Function<String, Optional<JsonObject>> NESTED =
        name -> Optional.ofNullable(
            Map.of(
                "inner", "{\"type\":\"maven-group\",\"members\":[\"leaf\",\"deeper\"]}",
                "deeper", "{\"type\":\"maven-group\",\"members\":[\"deep-leaf\"]}",
                "ruled", "{\"type\":\"maven-group\",\"members\":[\"central\"],"
                    + "\"routing\":[{\"member\":\"central\",\"prefix\":\"org/\"}]}",
                "leaf", "{\"type\":\"maven\"}",
                "hosted", "{\"type\":\"maven\"}",
                "central", "{\"type\":\"maven-proxy\"}"
            ).get(name)
        ).map(RoutingRulesCheckTest::json);

    private static List<String> problems(final String repo) {
        return new RoutingRulesCheck(
            RoutingRulesCheckTest.json(repo), name -> Optional.empty()
        ).problems();
    }

    private static JsonObject json(final String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }
}
