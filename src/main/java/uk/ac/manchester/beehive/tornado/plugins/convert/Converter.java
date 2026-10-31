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

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.ai.AnswerProgress;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmResponse;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModelBuilder;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationHarnessGenerator;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationModel;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Converts one method: asks the model for a kernel, repairs what can be repaired mechanically,
 * checks the result, runs it with TornadoVM against the original method if a {@link Verifier} is
 * given, and sends the problems back to the model, for up to {@link #MAX_ROUNDS} answers. Runs on a background thread; PSI is read in non-blocking read actions once indexing
 * is done, since the project may be re-indexed while the model answers.
 */
final class Converter {

    static final int MAX_ROUNDS = 3;
    // Below this an answer cannot hold a kernel, with or without reasoning
    private static final int MIN_ANSWER_TOKENS = 1024;
    // Code takes more tokens per character than prose; jitllm itself estimates 4 characters a token
    private static final int CHARS_PER_TOKEN = 3;
    // The chat template's tokens around the messages
    private static final int TEMPLATE_TOKENS = 512;

    /**
     * A converted method.
     *
     * @param kernelText the kernel, for the preview and Accept to place
     * @param methodText the method as it was sent to the model
     * @param problems   what the checks still report after the last round; empty if it passed
     * @param repaired   whether the plugin moved a misplaced {@code @Parallel} itself
     * @param callers    the calls that no longer match because the kernel's signature differs
     * @param outcome    the run of the kernel against the original method, or {@code null} if it
     *                   was not run (no verifier, or the static checks failed)
     * @param target     the API the kernel uses
     * @param harness    what the verification harness needs to run the kernel again (to time it),
     *                   or {@code null}
     */
    record Result(@NotNull String kernelText, @NotNull String methodName, @NotNull String methodText,
                  @NotNull List<String> problems, int rounds, boolean repaired, @NotNull String model,
                  boolean fellBack, @NotNull String explanation, int callers,
                  @Nullable KernelVerifier.Outcome outcome, @NotNull Target target,
                  @Nullable VerificationModel harness) {

        /** Whether the kernel ran and gave the original method's outputs. */
        boolean verified() {
            return outcome != null && outcome.status() == KernelVerifier.Status.EQUAL && problems.isEmpty();
        }
    }

    /** Runs a candidate that passed the static checks. Called on the conversion's background thread. */
    interface Verifier {
        @NotNull KernelVerifier.Outcome verify(@NotNull VerificationModel model);
    }

    private record Attempt(String kernelText, List<String> problems, boolean repaired, boolean signatureChanged,
                           @Nullable VerificationModel model, @Nullable KernelVerifier.Outcome outcome) {

        Attempt verified(KernelVerifier.Outcome outcome) {
            boolean failed = outcome.status() == KernelVerifier.Status.KERNEL_FAILED
                    || outcome.status() == KernelVerifier.Status.DIFFERENT;
            return new Attempt(kernelText, failed ? outcome.details() : problems, repaired, signatureChanged, model, outcome);
        }

        Attempt withProblems(List<String> problems) {
            return new Attempt(kernelText, problems, repaired, signatureChanged, model, outcome);
        }
    }

    /** A read of the PSI that may find the method gone. */
    private interface Read<T> {
        T compute() throws ConversionException;
    }

    private final LlmProvider provider;
    private final boolean structured;
    private final int maxTokens;
    private final @Nullable Integer contextLength;
    private final @Nullable Verifier verifier;
    private final Target target;
    private final @Nullable KernelVerifier.Device device;

    /**
     * @param structured    whether the provider follows a JSON schema
     * @param maxTokens     the limit of each answer, thinking included
     * @param contextLength the server's context, prompt and answer together, if it reports it;
     *                      each answer's limit is then what the context leaves, up to {@code maxTokens}
     * @param verifier      runs the candidates that pass the static checks, or {@code null}
     * @param target        the API to convert to
     * @param device        the device the kernel will run on, which the model is told, or {@code null}
     */
    Converter(@NotNull LlmProvider provider, boolean structured, int maxTokens, @Nullable Integer contextLength,
              @Nullable Verifier verifier, @NotNull Target target, @Nullable KernelVerifier.Device device) {
        this.target = target;
        this.device = device;
        this.provider = provider;
        this.structured = structured;
        this.maxTokens = maxTokens;
        this.contextLength = contextLength;
        this.verifier = verifier;
    }

    /**
     * The limit of an answer to a prompt of {@code promptCharacters}: {@code maxTokens}, or less
     * if the context leaves less, estimated generously so that the server does not reject it.
     *
     * @return the limit, or {@code null} if the context leaves no room for an answer
     */
    static @Nullable Integer tokenLimit(int maxTokens, @Nullable Integer contextLength, int promptCharacters) {
        if (contextLength == null) {
            return maxTokens;
        }
        int room = contextLength - promptCharacters / CHARS_PER_TOKEN - TEMPLATE_TOKENS;
        return room < MIN_ANSWER_TOKENS ? null : Math.min(maxTokens, room);
    }

    @NotNull Result convert(@NotNull SmartPsiElementPointer<PsiMethod> pointer, @NotNull BooleanSupplier cancelled,
                            @NotNull Consumer<String> status) throws LlmException, ConversionException {
        Project project = pointer.getProject();
        String[] methodText = new String[1];
        String task = read(project, () -> {
            PsiMethod original = element(pointer);
            methodText[0] = original.getText();
            return Conversation.task(original, target, device);
        });
        Conversation conversation = new Conversation(task, structured, target);
        Attempt last = null;
        LlmResponse response = null;
        String explanation = "";
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            Integer limit = tokenLimit(maxTokens, contextLength, conversation.characters());
            if (limit == null) {
                // The rejected answers have filled the context
                String full = MessageBundle.message("convert.error.contextFull", contextLength);
                return stop(pointer, methodText[0], last, full, round - 1, response, explanation);
            }
            AnswerStatus waiting = new AnswerStatus(round, status, System.nanoTime());
            waiting.show();
            // The cancellation flag is polled every 100 ms while the answer streams, so the time is shown too
            response = provider.complete(conversation.request(limit), () -> {
                waiting.show();
                return cancelled.getAsBoolean();
            }, waiting);
            if (response.truncated()) {
                // A cut-off answer is not the model's mistake: sending it back would only repeat it
                String cutOff = MessageBundle.message("convert.error.truncated", limit);
                return stop(pointer, methodText[0], last, cutOff, round, response, explanation);
            }
            Answer answer = Answer.parse(response.text());
            List<String> problems;
            if (answer != null && answer.notParallel() != null) {
                throw new NotParallelException(MessageBundle.message("convert.notParallel", answer.notParallel()));
            }
            if (answer == null) {
                problems = List.of("The answer contains no Java method.");
            } else {
                status.accept(MessageBundle.message("convert.progress.checking"));
                Attempt attempt = read(project, () -> check(pointer, answer.code(), target, verifier != null));
                if (attempt.problems().isEmpty() && verifier != null && attempt.model() != null) {
                    status.accept(MessageBundle.message("convert.progress.running"));
                    attempt = attempt.verified(verifier.verify(attempt.model()));
                }
                problems = attempt.problems();
                if (attempt.kernelText() != null) {
                    last = attempt;
                    explanation = answer.explanation();
                }
                if (problems.isEmpty()) {
                    return result(pointer, methodText[0], last, round, response, explanation);
                }
            }
            if (cancelled.getAsBoolean()) {
                throw new LlmException(LlmException.Kind.CANCELLED, "Cancelled");
            }
            if (round < MAX_ROUNDS) {
                conversation.reject(response.text(), problems);
            }
        }
        if (last == null) {
            throw new ConversionException(MessageBundle.message("convert.error.noCode", MAX_ROUNDS));
        }
        return result(pointer, methodText[0], last, MAX_ROUNDS, response, explanation);
    }

    // Ends the conversion early: with the last candidate and the reason as a problem, or with an error
    private Result stop(SmartPsiElementPointer<PsiMethod> pointer, String methodText, @Nullable Attempt last,
                               String reason, int rounds, @Nullable LlmResponse response, String explanation)
            throws ConversionException {
        if (last == null || response == null) {
            throw new ConversionException(reason);
        }
        List<String> problems = new ArrayList<>(last.problems());
        problems.add(reason);
        return result(pointer, methodText, last.withProblems(problems), rounds, response, explanation);
    }

    private Result result(SmartPsiElementPointer<PsiMethod> pointer, String methodText, Attempt attempt, int rounds,
                                 LlmResponse response, String explanation) throws ConversionException {
        // A kernel that TornadoVM could not compile or run, or whose results differ from the method's, is not offered
        KernelVerifier.Outcome outcome = attempt.outcome();
        if (outcome != null && (outcome.status() == KernelVerifier.Status.DIFFERENT
                || outcome.status() == KernelVerifier.Status.KERNEL_FAILED)) {
            String detail = outcome.details().isEmpty() ? "" : outcome.details().get(0);
            throw new ConversionException(MessageBundle.message(outcome.status() == KernelVerifier.Status.DIFFERENT
                    ? "convert.error.wrongResults" : "convert.error.kernelFailed", rounds, detail));
        }
        String[] name = new String[1];
        int callers = read(pointer.getProject(), () -> {
            PsiMethod original = element(pointer);
            name[0] = original.getName();
            return attempt.signatureChanged() ? MethodReferencesSearch.search(original).findAll().size() : 0;
        });
        return new Result(attempt.kernelText(), name[0], methodText, attempt.problems(), rounds, attempt.repaired(),
                response.model(), response.fellBack(), explanation, callers, attempt.outcome(), target,
                attempt.model());
    }

    /**
     * The status line while the model answers: how long it has taken, and whether it thinks or
     * writes, with how much it has written. Updated at most once a second, from either thread.
     */
    static final class AnswerStatus implements AnswerProgress {
        private final int round;
        private final Consumer<String> status;
        private final long start;
        private boolean writing;
        private boolean thinking;
        private int characters;
        private long shownSecond = -1;
        private String shown;

        AnswerStatus(int round, Consumer<String> status, long start) {
            this.round = round;
            this.status = status;
            this.start = start;
        }

        @Override
        public synchronized void streamed(boolean writing, int characters) {
            this.writing |= writing;
            this.thinking |= !writing;
            this.characters = characters;
            show();
        }

        synchronized void show() {
            show(System.nanoTime());
        }

        synchronized void show(long now) {
            long second = (now - start) / 1_000_000_000L;
            String text = text(second);
            if (second != shownSecond || !text.equals(shown)) {
                shownSecond = second;
                shown = text;
                status.accept(text);
            }
        }

        String text(long seconds) {
            String elapsed = seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s";
            if (writing) {
                return MessageBundle.message("convert.progress.writing", round, MAX_ROUNDS, characters, elapsed);
            }
            return MessageBundle.message(thinking ? "convert.progress.thinking" : "convert.progress.round", round, MAX_ROUNDS, elapsed);
        }
    }

    // Puts the candidate in place of the method in a copy of the file, repairs and checks it
    private static Attempt check(SmartPsiElementPointer<PsiMethod> pointer, String code, Target target, boolean harness)
            throws ConversionException {
        PsiMethod original = element(pointer);
        PsiFile copy = (PsiFile) original.getContainingFile().copy();
        KernelReplacement.Replaced replaced = KernelReplacement.replace(PsiTreeUtil.findSameElementInCopy(original, copy), code);
        if (replaced == null) {
            return new Attempt(null, List.of("The answer must contain exactly one method, named `"
                    + original.getName() + "`."), false, false, null, null);
        }
        PsiMethod kernel = replaced.kernel();
        List<String> problems = CandidateChecker.problems(original, kernel, target);
        VerificationModel model = null;
        KernelVerifier.Outcome outcome = null;
        if (harness && problems.isEmpty()) {
            // The harness would not compile, through no fault of the kernel's: it is not run, and the model not told
            List<String> notCopied = RunnableKernel.notCopied(kernel);
            if (notCopied.isEmpty()) {
                model = verificationModel(original, kernel, target);
            } else {
                outcome = new KernelVerifier.Outcome(KernelVerifier.Status.NOT_VERIFIED, List.of(MessageBundle.message(
                        "convert.verify.notCopied", "`" + String.join("`, `", notCopied) + "`")), null, null);
            }
        }
        return new Attempt(replaced.kernelText(), problems, replaced.repaired(), !sameSignature(original, kernel), model, outcome);
    }

    // Everything the harness needs, copied out of the PSI
    private static VerificationModel verificationModel(PsiMethod original, PsiMethod kernel, Target target) {
        PsiType returnType = original.getReturnType();
        // Only a kernel with a context has a grid, and only then has CandidateChecker checked it
        KernelGrid grid = target.context() == null ? null : KernelGrid.parse(kernel.getText());
        // Only the fields the code uses: the others' initializers would run for nothing, and they
        // are not checked like the kernel (see RunnableKernel)
        return new VerificationModel(HarnessModelBuilder.build(kernel, KernelVerifier.className(), false), parameters(kernel),
                HarnessModelBuilder.build(original, VerificationHarnessGenerator.ORIGINAL_CLASS, false), parameters(original),
                original.hasModifierProperty(PsiModifier.STATIC), returnType == null ? "void" : returnType.getCanonicalText(),
                grid == null ? List.of() : grid.global(), grid == null ? List.of() : grid.local());
    }

    private static List<VerificationModel.Parameter> parameters(PsiMethod method) {
        List<VerificationModel.Parameter> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parameters.add(new VerificationModel.Parameter(parameter.getName(), parameter.getType().getCanonicalText()));
        }
        return parameters;
    }

    // Whether the method's callers still compile against the kernel
    private static boolean sameSignature(PsiMethod original, PsiMethod kernel) {
        if (!canonical(original.getReturnType()).equals(canonical(kernel.getReturnType()))
                || original.hasModifierProperty(PsiModifier.STATIC) != kernel.hasModifierProperty(PsiModifier.STATIC)) {
            return false;
        }
        PsiParameter[] expected = original.getParameterList().getParameters();
        PsiParameter[] actual = kernel.getParameterList().getParameters();
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!canonical(expected[i].getType()).equals(canonical(actual[i].getType()))) {
                return false;
            }
        }
        return true;
    }

    private static String canonical(@Nullable PsiType type) {
        return type == null ? "" : type.getCanonicalText();
    }

    // Waits for indexing to finish, and gives way to write actions instead of blocking them
    private static <T> T read(Project project, Read<T> read) throws ConversionException {
        ConversionException[] failure = new ConversionException[1];
        T value = ReadAction.nonBlocking(() -> {
                    failure[0] = null;
                    try {
                        return read.compute();
                    } catch (ConversionException e) {
                        failure[0] = e;
                        return null;
                    }
                })
                .inSmartMode(project)
                .expireWhen(project::isDisposed)
                .executeSynchronously();
        if (failure[0] != null) {
            throw failure[0];
        }
        return value;
    }

    private static PsiMethod element(SmartPsiElementPointer<PsiMethod> pointer) throws ConversionException {
        PsiMethod method = pointer.getElement();
        if (method == null || method.getContainingClass() == null) {
            throw new ConversionException(MessageBundle.message("convert.error.methodGone"));
        }
        return method;
    }

    /** A conversion that cannot go on, with a message for the user. */
    static class ConversionException extends Exception {
        ConversionException(@Nullable String message) {
            super(message);
        }
    }

    /** The model answered that the method cannot be parallelised: no kernel, for a reason the user should see. */
    static final class NotParallelException extends ConversionException {
        NotParallelException(@NotNull String message) {
            super(message);
        }
    }
}
