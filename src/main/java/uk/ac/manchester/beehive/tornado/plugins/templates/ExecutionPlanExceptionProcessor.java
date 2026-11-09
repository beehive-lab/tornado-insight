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

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.impl.TemplateContext;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TemplateOptionalProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiResourceList;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * After the {@code tep} template, makes its try-with-resources compile: the implicit
 * {@code close()} of a {@code TornadoExecutionPlan} throws the checked
 * {@code TornadoExecutionPlanException}. Adds it to the {@code throws} clause of the enclosing
 * method or, where a method cannot declare it (a lambda, an initializer, an override of a
 * method that does not declare it), a {@code catch} that rethrows it unchecked. Does nothing if
 * the exception is already handled there.
 */
public class ExecutionPlanExceptionProcessor implements TemplateOptionalProcessor {

    static final String GROUP = "TornadoVM";
    static final String TEMPLATE = "tep";
    static final String EXCEPTION = "uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException";

    @Override
    public void processText(Project project, Template template, Document document, RangeMarker templateRange, Editor editor) {
        if (!templateRange.isValid()) {
            return;
        }
        PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
        documents.commitDocument(document);
        PsiFile file = documents.getPsiFile(document);
        if (!(file instanceof PsiJavaFile)) {
            return;
        }
        PsiTryStatement tryStatement = tryStatementAt(file, templateRange.getStartOffset());
        PsiResourceList resources = tryStatement != null ? tryStatement.getResourceList() : null;
        if (resources == null) {
            return;
        }
        PsiClass exceptionClass = JavaPsiFacade.getInstance(project).findClass(EXCEPTION, file.getResolveScope());
        if (exceptionClass == null) {
            return;
        }
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiClassType exceptionType = factory.createType(exceptionClass);
        // From the resources, so that the try statement's own catch sections count
        if (ExceptionUtil.isHandled(exceptionType, resources)) {
            return;
        }
        JavaCodeStyleManager codeStyle = JavaCodeStyleManager.getInstance(project);
        PsiMethod method = PsiTreeUtil.getParentOfType(tryStatement, PsiMethod.class, true, PsiLambdaExpression.class, PsiClass.class);
        if (method != null && mayDeclare(method, exceptionType)) {
            PsiUtil.addException(method, exceptionClass);
            codeStyle.shortenClassReferences(method.getThrowsList());
        } else {
            String name = codeStyle.suggestUniqueVariableName("e", tryStatement, false);
            PsiCatchSection section = (PsiCatchSection) tryStatement.add(factory.createCatchSection(exceptionType, name, tryStatement));
            PsiCodeBlock body = section.getCatchBlock();
            if (body != null) {
                // Its own body, not the IDE's "Catch Statement Body" file template
                for (PsiStatement statement : body.getStatements()) {
                    statement.delete();
                }
                body.add(factory.createStatementFromText("throw new RuntimeException(" + name + ");", body));
            }
            codeStyle.shortenClassReferences(section);
        }
        documents.doPostponedOperationsAndUnblockDocument(document);
    }

    private static @Nullable PsiTryStatement tryStatementAt(PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        // The template may start after whitespace that the reformat moved
        while (element != null && element.getText().isBlank()) {
            element = element.getNextSibling() != null ? element.getNextSibling() : file.findElementAt(element.getTextRange().getEndOffset());
        }
        return PsiTreeUtil.getParentOfType(element, PsiTryStatement.class, false);
    }

    /** Whether {@code method} may declare the exception: every method it overrides declares it, or a supertype. */
    private static boolean mayDeclare(PsiMethod method, PsiClassType exception) {
        for (PsiMethod overridden : method.findSuperMethods()) {
            if (!ExceptionUtil.isHandledBy(exception, overridden.getThrowsList().getReferencedTypes())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getOptionName() {
        return "Declare TornadoExecutionPlanException";
    }

    @Override
    public boolean isEnabled(Template template) {
        return TEMPLATE.equals(template.getKey()) && template instanceof TemplateImpl impl
                && GROUP.equals(impl.getGroupName());
    }

    // Applies to tep only, so no check box in the template's options
    @Override
    public boolean isVisible(@NotNull Template template, @NotNull TemplateContext context) {
        return false;
    }
}
