/*
 * Copyright (c) 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.inspector;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ExternalLibraryInspectionTest extends TornadoTestCase {

    // Module library (a compiled class outside the project's source roots) providing
    // org.example.lib.ExtLib, so a call into it is a genuine library call rather than
    // a user-project helper
    private static final LightProjectDescriptor WITH_LIBRARY = new DefaultLightProjectDescriptor() {
        @Override
        public void configureModule(@NotNull Module module, @NotNull ModifiableRootModel model, @NotNull ContentEntry contentEntry) {
            super.configureModule(module, model, contentEntry);
            VirtualFile root = compileLibrary();
            Library.ModifiableModel library = model.getModuleLibraryTable().createLibrary("ext-lib").getModifiableModel();
            library.addRoot(root, OrderRootType.CLASSES);
            library.commit();
        }
    };

    // Writes org/example/lib/ExtLib.class with "public static int scale(int)" using the
    // platform's bundled ASM (the test JVM has no javac)
    private static VirtualFile compileLibrary() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "org/example/lib/ExtLib", null, "java/lang/Object", null);
        MethodVisitor scale = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scale", "(I)I", null, null);
        scale.visitCode();
        scale.visitVarInsn(Opcodes.ILOAD, 0);
        scale.visitInsn(Opcodes.IRETURN);
        scale.visitMaxs(0, 0);
        scale.visitEnd();
        writer.visitEnd();
        try {
            Path root = Files.createTempDirectory("tornado-insight-ext-lib");
            Path pkg = Files.createDirectories(root.resolve("org/example/lib"));
            Files.write(pkg.resolve("ExtLib.class"), writer.toByteArray());
            return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return WITH_LIBRARY;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        myFixture.enableInspections(new ExternalLibraryInspection());
    }

    public void testUserProjectHelpersAreNotFlagged() {
        myFixture.addClass("""
                package demo.util;
                public class MathUtil {
                    public static int square(int x) { return x * x; }
                }""");
        myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import demo.util.MathUtil;

                class Demo {
                    static int twice(int x) { return 2 * x; }

                    static void kernel(int[] a) {
                        int[] copy = a.clone();
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = twice(i) + MathUtil.square(copy[i]) + Math.abs(i);
                        }
                    }
                }""");

        assertEmpty(externalLibraryProblems());
    }

    public void testLibraryCallIsFlagged() {
        myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = org.example.lib.ExtLib.scale(i);
                        }
                    }
                }""");

        assertSize(1, externalLibraryProblems());
    }

    private List<HighlightInfo> externalLibraryProblems() {
        String message = MessageBundle.message("inspection.externalLibrary");
        return myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null && info.getDescription().startsWith(message))
                .toList();
    }
}
