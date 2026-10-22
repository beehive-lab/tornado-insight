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

package uk.ac.manchester.beehive.tornado.plugins.templates;

import com.intellij.codeInsight.template.JavaCodeContextType;
import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Live template contexts for the TornadoVM templates.
 * <p>
 * Each context reuses one of the Java contexts (statement or declaration) to decide where
 * in the code a template applies, and additionally requires a TornadoVM API class to be
 * resolvable from the file, so the templates are only offered in modules that depend on
 * TornadoVM and never expand into code that cannot compile.
 * </p>
 */
public abstract class TornadoVMContextType extends JavaCodeContextType {

    private static final String TASK_GRAPH = "uk.ac.manchester.tornado.api.TaskGraph";
    private static final String TILE_CONTEXT = "uk.ac.manchester.tornado.api.tile.TileContext";

    private final JavaCodeContextType javaContext;
    private final String requiredClass;

    protected TornadoVMContextType(String presentableName, JavaCodeContextType javaContext, String requiredClass) {
        super(presentableName);
        this.javaContext = javaContext;
        this.requiredClass = requiredClass;
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext templateActionContext) {
        return javaContext.isInContext(templateActionContext) && isResolvable(templateActionContext.getFile());
    }

    @Override
    protected boolean isInContext(@NotNull PsiElement element) {
        // Unused: the location check is delegated to the wrapped Java context above.
        return false;
    }

    private boolean isResolvable(PsiFile file) {
        if (DumbService.isDumb(file.getProject())) return false;
        return JavaPsiFacade.getInstance(file.getProject()).findClass(requiredClass, file.getResolveScope()) != null;
    }

    /** Statement positions in a TornadoVM module: TaskGraph and TornadoExecutionPlan templates. */
    public static final class Statement extends TornadoVMContextType {
        public Statement() {
            super("TornadoVM statement", new JavaCodeContextType.Statement(), TASK_GRAPH);
        }
    }

    /** Class member positions in a TornadoVM module: @Parallel and KernelContext kernel templates. */
    public static final class Declaration extends TornadoVMContextType {
        public Declaration() {
            super("TornadoVM declaration", new JavaCodeContextType.Declaration(), TASK_GRAPH);
        }
    }

    /** Class member positions in a module whose TornadoVM version ships TileContext (7.0.0+). */
    public static final class TileDeclaration extends TornadoVMContextType {
        public TileDeclaration() {
            super("TornadoVM TileContext declaration", new JavaCodeContextType.Declaration(), TILE_CONTEXT);
        }
    }
}
