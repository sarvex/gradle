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

package org.gradle.api.internal.initialization;

import com.google.common.util.concurrent.Runnables;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderId;

/**
 * Provides implementations of classloader ids
 */
public class ClassLoaderIds {

    /**
     * class loader that is a part of class loader scope (hierarchy)
     */
    static ClassLoaderId scopeNode(String node) {
        return new DefaultClassLoaderId("scope:" + node, Runnables.doNothing());
    }

    /**
     * build script classloader
     */
    public static ClassLoaderId buildScript(String fileName, Runnable whenRefreshed) {
        return new DefaultClassLoaderId("build script:" + fileName, whenRefreshed);
    }

    /**
     * test task classpath classloader
     */
    public static ClassLoaderId testTaskClasspath(String testTaskPath) {
        return new DefaultClassLoaderId("test classpath:" + testTaskPath, Runnables.doNothing());
    }

    private static class DefaultClassLoaderId implements ClassLoaderId {
        private final String node;
        private final Runnable whenRefreshed;

        public DefaultClassLoaderId(String node, Runnable whenRefreshed) {
            this.node = node;
            this.whenRefreshed = whenRefreshed;
        }
        public String toString() {
            return node;
        }
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DefaultClassLoaderId)) {
                return false;
            }

            DefaultClassLoaderId that = (DefaultClassLoaderId) o;

            return node.equals(that.node);

        }
        public int hashCode() {
            return node.hashCode();
        }

        public void notifyRefreshed() {
            whenRefreshed.run();
        }
    }
}