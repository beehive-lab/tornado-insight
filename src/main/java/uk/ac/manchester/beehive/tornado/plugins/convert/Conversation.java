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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmRequest;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The messages exchanged with the model while one method is converted: the task, then for each
 * rejected answer the answer and the problems found in it.
 */
final class Conversation {

    private final String system;
    private final boolean structured;
    private final List<LlmRequest.Message> messages = new ArrayList<>();

    /**
     * @param structured whether the provider follows a JSON schema; otherwise the answer is
     *                   asked for as a fenced code block
     * @param target     the API to convert to, whose rules are the system prompt
     */
    Conversation(@NotNull String task, boolean structured, @NotNull Target target) {
        this.system = ConversionRules.text(target);
        this.structured = structured;
        messages.add(new LlmRequest.Message(LlmRequest.Role.USER, task + "\n\n" + format()));
    }

    /**
     * @param maxTokens the limit of the answer, thinking included
     */
    @NotNull LlmRequest request(int maxTokens) {
        return new LlmRequest(system, List.copyOf(messages), maxTokens, structured ? schema() : null);
    }

    /** The length of the prompt so far, system prompt included. */
    int characters() {
        return system.length() + messages.stream().mapToInt(message -> message.text().length()).sum();
    }

    /** Records a rejected answer and asks for a corrected one. */
    void reject(@NotNull String answer, @NotNull List<String> problems) {
        StringBuilder feedback = new StringBuilder("The kernel is not accepted yet. Problems:\n");
        for (String problem : problems) {
            feedback.append("- ").append(problem).append('\n');
        }
        feedback.append("\nFix them and answer again with the complete method. ").append(format());
        messages.add(new LlmRequest.Message(LlmRequest.Role.ASSISTANT, answer));
        messages.add(new LlmRequest.Message(LlmRequest.Role.USER, feedback.toString()));
    }

    /**
     * The first message for a conversion to {@code target}, naming the device the kernel will run
     * on if it is known. Must be called with read access.
     */
    static @NotNull String task(@NotNull PsiMethod original, @NotNull Target target, @Nullable KernelVerifier.Device device) {
        StringBuilder task = new StringBuilder();
        task.append("Convert this method into a TornadoVM kernel that uses the ").append(target.displayName())
                .append(" and replaces it, named `").append(original.getName()).append("`:\n\n")
                .append("```java\n").append(original.getText().strip()).append("\n```\n");
        if (device != null) {
            task.append("\nThe kernel will run on ").append(device.name()).append(", with TornadoVM's ")
                    .append(device.backend()).append(" backend.\n");
        }
        List<String> context = context(original);
        if (!context.isEmpty()) {
            task.append("\nThe project code it uses, for reference (do not repeat it in your answer):\n\n```java\n");
            task.append(String.join("\n\n", context)).append("\n```\n");
        }
        return task.toString().strip();
    }

    private String format() {
        return structured
                ? "Put the complete kernel method in `code`, at most three sentences on what you parallelised in `explanation`,"
                        + " and `true` in `parallelizable`. If the method cannot be parallelised, put `false` in `parallelizable`,"
                        + " an empty `code`, and why in `explanation`."
                : "Answer with the complete kernel method in one ```java code block, then at most three sentences on what you"
                        + " parallelised. If the method cannot be parallelised, answer only `" + Answer.NOT_PARALLEL
                        + "` followed by why, in at most three sentences, and no code.";
    }

    // The source methods the original calls and the source fields it reads, in the order used
    private static List<String> context(PsiMethod original) {
        Set<String> context = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(original, PsiMethodCallExpression.class)) {
            PsiMethod callee = call.resolveMethod();
            if (callee != null && callee != original && isSource(callee) && callee.getBody() != null) {
                context.add(callee.getText().strip());
            }
        }
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(original, PsiReferenceExpression.class)) {
            if (reference.resolve() instanceof PsiField field && isSource(field)) {
                context.add(field.getText().strip());
            }
        }
        return List.copyOf(context);
    }

    private static boolean isSource(PsiMethod method) {
        return !(method instanceof PsiCompiledElement) && method.getContainingFile() != null
                && method.getContainingFile().isPhysical();
    }

    private static boolean isSource(PsiField field) {
        PsiClass owner = field.getContainingClass();
        return !(field instanceof PsiCompiledElement) && owner != null && owner.getContainingFile() != null
                && owner.getContainingFile().isPhysical();
    }

    private static JsonObject schema() {
        return JsonParser.parseString("""
                {"type": "object",
                 "properties": {"code": {"type": "string"}, "explanation": {"type": "string"},
                                "parallelizable": {"type": "boolean"}},
                 "required": ["code", "explanation", "parallelizable"],
                 "additionalProperties": false}""").getAsJsonObject();
    }
}
