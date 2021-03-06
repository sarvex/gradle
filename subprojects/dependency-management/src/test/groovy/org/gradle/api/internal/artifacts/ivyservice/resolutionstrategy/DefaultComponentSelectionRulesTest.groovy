/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolutionstrategy

import org.gradle.api.Action
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.ComponentSelectionInternal
import org.gradle.api.internal.artifacts.DefaultComponentSelection
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.configurations.MutationValidator
import org.gradle.api.specs.Specs
import org.gradle.internal.Actions
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.rules.RuleAction
import org.gradle.internal.rules.RuleActionAdapter
import org.gradle.internal.typeconversion.NotationParser
import org.gradle.internal.typeconversion.UnsupportedNotationException
import spock.lang.Specification

import static org.gradle.api.internal.artifacts.configurations.MutationValidator.MutationType.STRATEGY

class DefaultComponentSelectionRulesTest extends Specification {
    static final GROUP = "group"
    static final MODULE = "module"
    RuleActionAdapter<ComponentSelection> adapter = Mock(RuleActionAdapter)
    NotationParser<Object, String> notationParser = Mock(NotationParser)
    DefaultComponentSelectionRules rules = new DefaultComponentSelectionRules(adapter, notationParser)
    ComponentSelectionInternal componentSelection
    def ruleAction = Mock(RuleAction)
    def ruleSource = new Object()

    def setup() {
        def componentIdentifier = DefaultModuleComponentIdentifier.newId(GROUP, MODULE, "version")
        componentSelection = new DefaultComponentSelection(componentIdentifier)
    }

    def "add closure rule that applies to all components"() {
        def input = { ComponentSelection cs ->  }

        when:
        rules.all input

        then:
        1 * adapter.createFromClosure(ComponentSelection, input) >> ruleAction

        and:
        rules.rules.size() == 1
        rules.rules[0].action == ruleAction
        rules.rules[0].spec == Specs.satisfyAll()
    }

    def "add closure rule that applies to module"() {
        def input = { ComponentSelection cs ->  }
        def notation = "${GROUP}:${MODULE}"

        when:
        rules.withModule(notation, input)

        then:
        1 * adapter.createFromClosure(ComponentSelection, input) >> ruleAction
        1 * notationParser.parseNotation(notation) >> DefaultModuleIdentifier.newId(GROUP, MODULE)

        and:
        rules.rules.size() == 1
        rules.rules[0].action == ruleAction
        rules.rules[0].spec.target == DefaultModuleIdentifier.newId(GROUP, MODULE)
    }

    def "add action rule that applies to all components"() {
        def Action<ComponentSelection> action = Mock(Action)

        when:
        rules.all action

        then:
        1 * adapter.createFromAction(action) >> ruleAction

        and:
        rules.rules.size() == 1
        rules.rules[0].action == ruleAction
        rules.rules[0].spec == Specs.satisfyAll()
    }

    def "add action rule that applies to module"() {
        def Action<ComponentSelection> action = Mock(Action)
        def notation = "${GROUP}:${MODULE}"

        when:
        rules.withModule(notation, action)

        then:
        1 * adapter.createFromAction(action) >> ruleAction
        1 * notationParser.parseNotation(notation) >> DefaultModuleIdentifier.newId(GROUP, MODULE)

        and:
        rules.rules.size() == 1
        rules.rules[0].action == ruleAction
        rules.rules[0].spec.target == DefaultModuleIdentifier.newId(GROUP, MODULE)
    }

    def "add rule source rule that applies to all components"() {
        when:
        rules.all ruleSource

        then:
        1 * adapter.createFromRuleSource(ComponentSelection, ruleSource) >> ruleAction

        and:
        rules.rules.size() == 1
        rules.rules[0].action == ruleAction
        rules.rules[0].spec == Specs.satisfyAll()
    }

    def "add rule source rule that applies to module"() {
        def notation = "${GROUP}:${MODULE}"

        when:
        rules.withModule(notation, ruleSource)

        then:
        1 * adapter.createFromRuleSource(ComponentSelection, ruleSource) >> ruleAction
        1 * notationParser.parseNotation(notation) >> DefaultModuleIdentifier.newId(GROUP, MODULE)

        and:
        rules.rules.size() == 1
        rules.rules[0].action == ruleAction
        rules.rules[0].spec.target == DefaultModuleIdentifier.newId(GROUP, MODULE)
    }

    def "propagates error creating rule for closure" () {
        when:
        rules.all { }

        then:
        def e = thrown(InvalidUserCodeException)
        e.message == "bad closure"

        and:
        1 * adapter.createFromClosure(ComponentSelection, _) >> { throw new InvalidUserCodeException("bad closure") }

        when:
        rules.withModule("group:module") { }

        then:
        e = thrown(InvalidUserCodeException)
        e.message == "bad targeted closure"

        and:
        1 * adapter.createFromClosure(ComponentSelection, _) >> { throw new InvalidUserCodeException("bad targeted closure") }
    }

    def "propagates error creating rule for rule source" () {
        when:
        rules.all ruleSource

        then:
        def e = thrown(InvalidUserCodeException)
        e.message == "bad rule source"

        and:
        1 * adapter.createFromRuleSource(ComponentSelection, ruleSource) >> { throw new InvalidUserCodeException("bad rule source") }

        when:
        rules.withModule("group:module", ruleSource)

        then:
        e = thrown(InvalidUserCodeException)
        e.message == "bad targeted rule source"

        and:
        1 * adapter.createFromRuleSource(ComponentSelection, ruleSource) >> { throw new InvalidUserCodeException("bad targeted rule source") }
    }

    def "propagates error creating rule for action" () {
        def action = Mock(Action)

        when:
        rules.all action

        then:
        def e = thrown(InvalidUserCodeException)
        e.message == "bad action"

        and:
        1 * adapter.createFromAction(action) >> { throw new InvalidUserCodeException("bad action") }

        when:
        rules.withModule("group:module", action)

        then:
        e = thrown(InvalidUserCodeException)
        e.message == "bad targeted action"

        and:
        1 * adapter.createFromAction(action) >> { throw new InvalidUserCodeException("bad targeted action") }
    }

    def "propagates error parsing module identifier for closure" () {
        def input = { ComponentSelection cs -> }
        def notation = "group:module:1.0"

        when:
        rules.withModule(notation, input)

        then:
        def e = thrown(InvalidUserCodeException)
        e.message == "Could not add a component selection rule for module '${notation}'."
        def cause = e.cause
        cause instanceof UnsupportedNotationException
        cause.notation == notation

        and:
        1 * notationParser.parseNotation(notation) >> { throw new UnsupportedNotationException(notation) }
    }

    def "propagates error parsing module identifier for action" () {
        def input = Mock(Action)
        def notation = "group:module:1.0"

        when:
        rules.withModule(notation, input)

        then:
        def e = thrown(InvalidUserCodeException)
        e.message == "Could not add a component selection rule for module '${notation}'."
        def cause = e.cause
        cause instanceof UnsupportedNotationException
        cause.notation == notation

        and:
        1 * notationParser.parseNotation(notation) >> { throw new UnsupportedNotationException(notation) }
    }

    def "ComponentSelectionSpec matches on group and name" () {
        def spec = new DefaultComponentSelectionRules.ComponentSelectionMatchingSpec(DefaultModuleIdentifier.newId(group, name))
        def candidate = Mock(ModuleComponentIdentifier) {
            1 * getGroup() >> "org.gradle"
            (0..1) * getModule() >> "api"
        }
        def selection = Stub(ComponentSelection) {
            getCandidate() >> candidate
        }

        expect:
        spec.isSatisfiedBy(selection) == matches

        where:
        group        | name  | matches
        "org.gradle" | "api" | true
        "org.gradle" | "lib" | false
        "com.gradle" | "api" | false
    }

    def "mutation is checked for public API"() {
        def checker = Mock(MutationValidator)
        rules.beforeChange(checker)

        when: rules.all(Actions.doNothing())
        then: 1 * checker.validateMutation(STRATEGY)

        when: rules.all(Closure.IDENTITY)
        then: 1 * checker.validateMutation(STRATEGY)

        when: rules.all(ruleSource)
        then: 1 * checker.validateMutation(STRATEGY)

        when: rules.withModule("something", Actions.doNothing())
        then: 1 * checker.validateMutation(STRATEGY)

        when: rules.withModule("something", Closure.IDENTITY)
        then: 1 * checker.validateMutation(STRATEGY)

        when: rules.withModule("something", ruleSource)
        then: 1 * checker.validateMutation(STRATEGY)
    }

    private class TestComponentSelectionAction implements Action<ComponentSelection> {
        boolean called = false

        @Override
        void execute(ComponentSelection componentSelection) {
            called = true
        }
    }
}
