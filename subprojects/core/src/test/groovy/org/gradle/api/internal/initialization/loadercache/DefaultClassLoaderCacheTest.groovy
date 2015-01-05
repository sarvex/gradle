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

package org.gradle.api.internal.initialization.loadercache

import org.gradle.internal.classloader.FilteringClassLoader
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification

class DefaultClassLoaderCacheTest extends Specification {

    def storage = [:]
    def cache = new DefaultClassLoaderCache(storage)
    def id1 = Mock(ClassLoaderId)
    def id2 = Mock(ClassLoaderId)

    @Rule TestNameTestDirectoryProvider testDirectoryProvider = new TestNameTestDirectoryProvider()

    TestFile file(String path) {
        testDirectoryProvider.testDirectory.file(path)
    }

    ClassPath classPath(String... paths) {
        new DefaultClassPath(paths.collect { file(it) } as Iterable<File>)
    }

    ClassLoader classLoader(ClassPath classPath) {
        new URLClassLoader(classPath.asURLArray)
    }

    def "class loaders are reused when parent and class path are the same"() {
        expect:
        def root = classLoader(classPath("root"))
        cache.get(id1, classPath("c1"), root, null) == cache.get(id1, classPath("c1"), root, null)
        cache.get(id1, classPath("c1"), root, null) != cache.get(id1, classPath("c1", "c2"), root, null)
    }

    def "class loaders with different ids are reused"() {
        expect:
        def root = classLoader(classPath("root"))
        cache.get(id1, classPath("c1"), root, null).is cache.get(id2, classPath("c1"), root, null)
    }

    def "parents are respected"() {
        expect:
        def root1 = classLoader(classPath("root1"))
        def root2 = classLoader(classPath("root2"))
        cache.get(id1, classPath("c1"), root1, null) != cache.get(id2, classPath("c1"), root2, null)
    }

    def "null parents are respected"() {
        expect:
        def root = classLoader(classPath("root"))
        cache.get(id1, classPath("c1"), null, null) == cache.get(id1, classPath("c1"), null, null)
        cache.get(id1, classPath("c1"), null, null) != cache.get(id1, classPath("c1"), root, null)
    }

    def "filters are respected"() {
        def root = classLoader(classPath("root"))
        def f1 = new FilteringClassLoader.Spec(["1"], [], [], [], [], [])
        def f2 = new FilteringClassLoader.Spec(["2"], [], [], [], [], [])

        expect:
        cache.get(id1, classPath("c1"), root, f1).is(cache.get(id1, classPath("c1"), root, f1))
        !cache.get(id1, classPath("c1"), root, f1).is(cache.get(id1, classPath("c1"), root, f2))
        storage.size() == 2
    }

    def "non filtered classloaders are reused"() {
        expect:
        def root = classLoader(classPath("root"))
        def f1 = new FilteringClassLoader.Spec(["1"], [], [], [], [], [])
        cache.get(id1, classPath("c1"), root, f1)
        storage.size() == 2
        cache.get(id1, classPath("c1"), root, null)
        storage.size() == 2
    }

    def "removes stale classloader"() {
        def root = classLoader(classPath("root"))
        cache.get(id1, classPath("c1"), root, null)
        def c2 = cache.get(id1, classPath("c2"), root, null)
        expect:
        storage.size() == 1
        c2.is cache.get(id1, classPath("c2"), root, null)
    }
}
