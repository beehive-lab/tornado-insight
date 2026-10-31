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

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmProvider;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationHarnessGenerator;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationModel;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.TornadoToolchain;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiProviders;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingsConfigurable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoNotifications;

/**
 * Starts the conversion of a method from the action or the intention: checks that it can be
 * converted, runs {@link Converter} as a cancellable background task, then shows the preview.
 */
public final class ConversionLauncher {

    /** The elements per array when a candidate is checked against the original method. */
    static final int VERIFY_LENGTH = 4096;
    /** Runs before the timed runs of a kernel, for the JIT and TornadoVM's kernel compilation. */
    static final int BENCHMARK_WARMUPS = 3;
    /** The timed runs of each kernel; the comparison takes the median. */
    static final int BENCHMARK_RUNS = 10;

    /**
     * The turn to convert: one conversion at a time, in all projects, in the order they were
     * started. They share the model server, which may answer one request at a time (a waiting
     * request would look stalled), and the GPU, where a second one would skew the timings.
     */
    private static final Semaphore TURN = new Semaphore(1, true);
    /** The method being converted, for the status of those waiting. */
    private static volatile @Nullable String converting;

    private ConversionLauncher() {
    }

    /**
     * Whether {@code method} can be converted: a method with a body, in a class, that is not
     * a kernel already. Must be called with read access.
     */
    public static boolean isConvertible(@Nullable PsiMethod method) {
        return method != null && !method.isConstructor() && method.getBody() != null
                && method.getContainingClass() != null && method.getContainingClass().getQualifiedName() != null
                && !TornadoKernels.isKernel(method);
    }

    /** Whether the TornadoVM API is on {@code method}'s classpath. Must be called with read access. */
    public static boolean hasTornadoApi(@NotNull PsiMethod method) {
        return JavaPsiFacade.getInstance(method.getProject()).findClass(TornadoKernels.PARALLEL, method.getResolveScope()) != null;
    }

    /**
     * Converts {@code method}; call on the EDT. The lookups run in the background, then the
     * dialog asks for the target, then the conversion runs as a background task, once the
     * conversions started before it are done.
     */
    public static void start(@NotNull Project project, @NotNull PsiMethod method) {
        if (!AiSettingState.getInstance().enabled) {
            var notification = TornadoNotifications.create(MessageBundle.message("convert.disabled.title"),
                    MessageBundle.message("convert.disabled.content"), NotificationType.INFORMATION);
            notification.addAction(NotificationAction.createSimpleExpiring(MessageBundle.message("convert.disabled.action"),
                    () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, AiSettingsConfigurable.class)));
            notification.notify(project);
            return;
        }
        SmartPsiElementPointer<PsiMethod> pointer = SmartPointerManager.createPointer(method);
        ReadAction.nonBlocking(() -> plan(project, pointer))
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), plan -> {
                    if (plan != null) {
                        run(project, pointer, plan);
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * What the dialog needs, looked up before it opens.
     *
     * @param noRun       why kernels cannot be run, or {@code null}
     * @param unavailable the targets the project cannot use, and why
     * @param error       why the method cannot be converted at all, or {@code null}
     */
    private record Plan(String name, KernelVerifier.EnvironmentCheck environment, @Nullable String noRun,
                        Map<Target, String> unavailable, @Nullable String error) {
    }

    // Reads the index and the disk (the TornadoVM SDK), so it runs in a background read action
    private static @Nullable Plan plan(Project project, SmartPsiElementPointer<PsiMethod> pointer) {
        PsiMethod method = pointer.getElement();
        if (!isConvertible(method)) {
            return null;
        }
        // Class and method, so that the notifications of several transformations can be told apart
        String name = method.getContainingClass().getName() + "." + method.getName();
        if (!hasTornadoApi(method)) {
            return new Plan(name, null, null, Map.of(), MessageBundle.message("convert.error.noApi"));
        }
        KernelVerifier.EnvironmentCheck environment = KernelVerifier.environment(project);
        List<String> parameterTypes = new ArrayList<>();
        List<VerificationModel.Parameter> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parameterTypes.add(parameter.getType().getCanonicalText());
            parameters.add(new VerificationModel.Parameter(parameter.getName(), parameter.getType().getCanonicalText()));
        }
        // The kernels are run against the method, which needs test data for its parameters
        PsiType returnType = method.getReturnType();
        String untestable = VerificationHarnessGenerator.unsupported(parameters,
                returnType == null ? "void" : returnType.getCanonicalText());
        String noRun = environment.problem() != null ? environment.problem()
                : untestable == null ? null : MessageBundle.message("convert.target.untestable", untestable);
        // Checked before the model is asked, so that a target the project cannot use is not offered
        Map<Target, String> unavailable = new EnumMap<>(Target.class);
        for (Target target : Target.values()) {
            String why = target.unavailable(parameterTypes,
                    context -> JavaPsiFacade.getInstance(project).findClass(context, method.getResolveScope()) != null);
            if (why != null) {
                unavailable.put(target, why);
            }
        }
        return new Plan(name, environment, noRun, unavailable, null);
    }

    // Asks for the target, then converts in the background; on the EDT
    private static void run(Project project, SmartPsiElementPointer<PsiMethod> pointer, Plan plan) {
        if (plan.error() != null) {
            error(project, plan.name(), plan.error());
            return;
        }
        String name = plan.name();
        KernelVerifier.EnvironmentCheck environment = plan.environment();
        Set<Target> possible = EnumSet.allOf(Target.class);
        possible.removeAll(plan.unavailable().keySet());
        ConversionTargetDialog.Choice choice = ConversionTargetDialog.choose(project, name, plan.noRun(), plan.unavailable());
        if (choice == null) {
            return;
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        new Task.Backgroundable(project, MessageBundle.message("convert.progress.title", name), true) {
            private Converter.Result result;
            private Comparison comparison;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                if (!waitForTurn(name, indicator)) {
                    return;
                }
                try {
                    LlmProvider provider = AiProviders.fromSettings();
                    boolean structured = provider.followsJsonSchema();
                    Integer ownLimit = provider.answerTokens();
                    // Otherwise the limit is the user's, and what the context leaves after the prompt may lower it
                    Integer context = ownLimit != null ? null : contextLength(provider, indicator);
                    int maxTokens = ownLimit != null ? ownLimit : AiSettingState.getInstance().localMaxTokens();
                    KernelVerifier.Probe probe = environment.toolchain() == null ? null
                            : KernelVerifier.probe(environment.toolchain(), indicator);
                    String problem = probe == null ? environment.problem() : probe.problem();
                    // Kernels are run only on a toolchain that works, so that its problems are not sent to the model
                    TornadoToolchain toolchain = problem == null ? environment.toolchain() : null;
                    KernelVerifier.Device device = probe == null ? null : probe.device();
                    Converter.Verifier verifier = verifier(toolchain, problem, choice.run(), indicator);
                    // Without kernels that run, Fastest would ask for every API and time none of them
                    if (choice.target() == null && problem != null) {
                        error(project, name, MessageBundle.message("convert.error.fastestNeedsRun", problem));
                        return;
                    }
                    if (choice.target() == Target.TILE_API && (device == null || !device.runsTiles())) {
                        error(project, name, MessageBundle.message("convert.error.tileNeedsCuda",
                                problem == null ? describe(device) : MessageBundle.message("convert.error.noDevice") + ": " + problem));
                        return;
                    }
                    if (choice.target() != null) {
                        result = new Converter(provider, structured, maxTokens, context, verifier, choice.target(), device)
                                .convert(pointer, indicator::isCanceled, indicator::setText2);
                    } else {
                        boolean cudaTiles = device != null && device.runsTiles();
                        comparison = compare(choice, Target.compared(possible, cudaTiles), toolchain, pointer, name, indicator,
                                target -> new Converter(provider, structured, maxTokens, context, verifier, target, device));
                    }
                } catch (LlmException e) {
                    if (e.kind() != LlmException.Kind.CANCELLED) {
                        error(project, name, e.getMessage());
                    }
                } catch (Converter.ConversionException e) {
                    error(project, name, e.getMessage());
                } finally {
                    converting = null;
                    TURN.release();
                }
            }

            @Override
            public void onSuccess() {
                if (result != null) {
                    ConversionPreviewDialog.open(project, pointer, result, name);
                } else if (comparison != null) {
                    ConversionPreviewDialog.open(project, pointer, comparison, name);
                }
            }
        }.queue();
    }

    // Converts to every target, times each verified kernel, and recommends one
    private static Comparison compare(ConversionTargetDialog.Choice choice, List<Target> targets,
                                      @Nullable TornadoToolchain toolchain,
                                      SmartPsiElementPointer<PsiMethod> pointer, String name, ProgressIndicator indicator,
                                      Function<Target, Converter> converters) throws LlmException {
        List<Comparison.Entry> entries = new ArrayList<>();
        indicator.setIndeterminate(false);
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            indicator.setFraction((double) i / targets.size());
            indicator.setText(MessageBundle.message("convert.progress.target", target.displayName(), i + 1, targets.size(),
                    MessageBundle.message("convert.progress.title", name)));
            Converter.Result result;
            try {
                result = converters.apply(target).convert(pointer, indicator::isCanceled,
                        indicator::setText2);
            } catch (Converter.ConversionException e) {
                entries.add(new Comparison.Entry(target, null, null, e.getMessage()));
                continue;
            } catch (LlmException e) {
                if (e.kind() == LlmException.Kind.CANCELLED) {
                    throw e;
                }
                entries.add(new Comparison.Entry(target, null, null, e.getMessage()));
                continue;
            }
            KernelVerifier.Outcome timed = null;
            if (toolchain != null && result.verified() && result.harness() != null) {
                indicator.setText2(MessageBundle.message("convert.progress.timing", target.displayName(), choice.length()));
                // The method in Java is timed once, with the first kernel that is timed
                boolean timeJava = entries.stream().noneMatch(entry -> entry.timing() != null && entry.timing().java() >= 0);
                timed = KernelVerifier.verify(toolchain, result.harness(), choice.length(), BENCHMARK_WARMUPS,
                        BENCHMARK_RUNS, timeJava, indicator);
            }
            entries.add(new Comparison.Entry(target, result, timed, null));
        }
        return Comparison.of(entries, choice.length(), BENCHMARK_RUNS, choice.threshold());
    }

    private static String describe(@Nullable KernelVerifier.Device device) {
        return device == null ? MessageBundle.message("convert.error.noDevice")
                : device.name() + " (" + device.backend() + ")";
    }

    // Runs each candidate on the device, if the user allowed it; otherwise, or without TornadoVM,
    // every candidate is reported as not run, and why
    private static Converter.Verifier verifier(@Nullable TornadoToolchain toolchain, @Nullable String problem, boolean run,
                                               ProgressIndicator indicator) {
        if (toolchain == null || !run) {
            String why = toolchain == null ? MessageBundle.message("convert.verify.noTornado", problem)
                    : MessageBundle.message("convert.verify.notAllowed");
            KernelVerifier.Outcome notRun = new KernelVerifier.Outcome(KernelVerifier.Status.NOT_VERIFIED,
                    List.of(why), null, null);
            return model -> notRun;
        }
        return model -> KernelVerifier.verify(toolchain, model, VERIFY_LENGTH, 0, 0, indicator);
    }

    // The context a local server reports, or null; a server without a model list still converts
    private static @Nullable Integer contextLength(LlmProvider provider, ProgressIndicator indicator) throws LlmException {
        try {
            return provider.describe(indicator::isCanceled).contextLength();
        } catch (LlmException e) {
            if (e.kind() == LlmException.Kind.CANCELLED) {
                throw e;
            }
            return null;
        }
    }

    // Waits until no other conversion runs; false if cancelled meanwhile
    private static boolean waitForTurn(String name, ProgressIndicator indicator) {
        try {
            while (!TURN.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                if (indicator.isCanceled()) {
                    return false;
                }
                String other = converting;
                indicator.setText2(other == null ? MessageBundle.message("convert.progress.queued")
                        : MessageBundle.message("convert.progress.waiting", other));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        converting = name;
        indicator.setText2("");
        return true;
    }

    // Notifications render HTML, and the message may quote a server's error text
    private static void error(Project project, String name, String message) {
        ApplicationManager.getApplication().invokeLater(() -> TornadoNotifications.create(
                MessageBundle.message("convert.error.title", name), StringUtil.escapeXmlEntities(message),
                NotificationType.ERROR).notify(project));
    }
}
