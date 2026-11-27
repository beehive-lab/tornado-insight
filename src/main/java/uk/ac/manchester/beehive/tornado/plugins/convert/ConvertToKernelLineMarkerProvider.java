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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiMethodUtil;
import com.intellij.psi.util.PsiTreeUtil;
import icons.TornadoIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import java.util.Collection;
import java.util.List;

/**
 * A brain icon in the gutter of each method that AI-assisted transformation can turn into a
 * TornadoVM kernel; a click starts it. Shown only while the transformation is enabled, in
 * modules that have the TornadoVM API, and for methods with a loop of their own, since a method
 * without one has no work to spread over GPU threads; not for {@code main}, the program's entry
 * point rather than a kernel. The editor menu and Alt+Enter still offer the others.
 * Users can hide it under <em>Settings | Editor | General | Gutter Icons</em>.
 * <p>
 * The checks resolve types and look up the TornadoVM API in the index, so they run in the
 * slow pass of the line marker daemon, in the background, rather than in
 * {@link #getLineMarkerInfo}.
 * </p>
 */
public class ConvertToKernelLineMarkerProvider extends LineMarkerProviderDescriptor {

    @Override
    public @NotNull String getName() {
        return MessageBundle.message("convert.gutter.name");
    }

    @Override
    public @NotNull Icon getIcon() {
        return TornadoIcons.AiTransform;
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super LineMarkerInfo<?>> result) {
        if (!AiSettingState.getInstance().enabled) {
            return;
        }
        // The elements are from one file, so its module has the TornadoVM API for all or none
        Boolean hasApi = null;
        for (PsiElement element : elements) {
            PsiMethod method = candidate(element);
            if (method == null) {
                continue;
            }
            if (hasApi == null) {
                hasApi = ConversionLauncher.hasTornadoApi(method);
            }
            if (!hasApi) {
                return;
            }
            result.add(marker((PsiIdentifier) element, method));
        }
    }

    /**
     * The method {@code element} names, if it is a convertible method with a loop: the marker
     * goes on the name, a leaf, as the platform expects.
     */
    static @Nullable PsiMethod candidate(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod method)
                || method.getNameIdentifier() != element || !hasLoop(method) || PsiMethodUtil.isMainMethod(method)) {
            return null;
        }
        return ConversionLauncher.isConvertible(method) ? method : null;
    }

    // A loop of the method itself: not one in a lambda, or in an anonymous or local class
    private static boolean hasLoop(PsiMethod method) {
        if (method.getBody() == null) {
            return false;
        }
        for (PsiLoopStatement loop : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiLoopStatement.class)) {
            if (PsiTreeUtil.getParentOfType(loop, PsiMethod.class, PsiLambdaExpression.class, PsiClass.class) == method) {
                return true;
            }
        }
        return false;
    }

    private static LineMarkerInfo<PsiIdentifier> marker(PsiIdentifier name, PsiMethod method) {
        String tooltip = MessageBundle.message("convert.gutter.tooltip", method.getName());
        GutterIconNavigationHandler<PsiIdentifier> start = (event, identifier) -> {
            if (identifier.isValid() && identifier.getParent() instanceof PsiMethod target) {
                // start() checks the method again in the background, before it asks anything
                ConversionLauncher.start(identifier.getProject(), target);
            }
        };
        return new LineMarkerInfo<>(name, name.getTextRange(), TornadoIcons.AiTransform, element -> tooltip, start,
                GutterIconRenderer.Alignment.RIGHT, () -> tooltip);
    }
}
