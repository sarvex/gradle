/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.launcher.cli.converter

import org.gradle.cli.CommandLineParser
import org.gradle.initialization.BuildLayoutParameters
import org.gradle.launcher.daemon.configuration.DaemonParameters
import spock.lang.Specification
import spock.lang.Unroll

class DaemonCommandLineConverterTest extends Specification {

    @Unroll
    def "converts daemon options - #options"() {
        when:
        def converted = convert(options)

        then:
        converted.enabled == enabled
        converted.usageConfiguredExplicitly == explicitlySet

        where:
        options                     | enabled | explicitlySet
        []                          | false   | false
        ['--no-daemon']             | false   | true
        ['--daemon']                | true    | true
        ['--no-daemon', '--daemon'] | true    | true
    }

    private DaemonParameters convert(Iterable args) {
        CommandLineParser parser = new CommandLineParser()
        def converter = new DaemonCommandLineConverter()
        converter.configure(parser)
        converter.convert(args, new DaemonParameters(new BuildLayoutParameters()))
    }
}
