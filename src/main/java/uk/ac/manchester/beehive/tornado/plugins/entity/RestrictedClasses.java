/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
 *  The University of Manchester.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.entity;

public enum RestrictedClasses {
    JAVA_LANG_SYSTEM("java.lang.System"),
    JAVA_LANG_RUNTIME("java.lang.Runtime"),
    JAVA_LANG_PROCESS("java.lang.Process"),
    JAVA_LANG_PROCESSBUILDER("java.lang.ProcessBuilder"),
    JAVA_LANG_THREAD("java.lang.Thread"),
    JAVA_IO("java.io"),
    JAVA_UTIL_CONCURRENT("java.util.concurrent"),
    JAVA_LANG_REFLECT("java.lang.reflect"),
    JAVA_NET("java.net"),
    JAVA_NIO("java.nio"),
    JAVA_SECURITY("java.security"),
    JAVA_SQL("java.sql");

    private final String className;

    RestrictedClasses(String className) {
        this.className = className;
    }

    public String getClassName() {
        return className;
    }

    public static boolean isRestrictedClass(String className) {
        for (RestrictedClasses restrictedClass : values()) {
            if (className.startsWith(restrictedClass.getClassName())) {
                return true;
            }
        }
        return false;
    }
}

