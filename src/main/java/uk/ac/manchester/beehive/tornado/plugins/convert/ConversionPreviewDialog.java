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

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScreenUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.convert.KernelReplacement.Placement;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoNotifications;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shows the file before and after the conversion; <em>Accept</em> adds the kernel beside the
 * method or replaces the method with it, as the user chooses, as one undoable command. A Kernel
 * API or Tile API kernel cannot stand in for the method in its callers, so it is added beside it
 * unless the user says otherwise. Both sides are made from the file as it is when the dialog
 * opens, since it may have been edited while the model answered. After <em>Fastest (measured)</em>
 * it shows the timings and lets the user pick the kernel, the recommended one first.
 */
final class ConversionPreviewDialog extends DialogWrapper {

    private final Project project;
    /** The class and method, for notifications. */
    private final String name;
    private final SmartPsiElementPointer<PsiMethod> pointer;
    private final List<Converter.Result> results;
    /** The file after each result, for each placement. */
    private final List<Map<Placement, String>> afters;
    private final @Nullable Comparison comparison;
    private final String before;
    private final String currentText;
    private final String besideName;
    private int selected;
    /** Replace by default, for every API; the user's choice stays when another kernel is picked. */
    private Placement placement = Placement.REPLACE;
    private DiffRequestPanel diff;
    private JBLabel summary;

    private ConversionPreviewDialog(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiMethod> pointer,
                                    @NotNull List<Converter.Result> results, @NotNull List<Map<Placement, String>> afters,
                                    int selected, @Nullable Comparison comparison, @NotNull String before,
                                    @NotNull String currentText, @NotNull String besideName, @NotNull String name) {
        super(project, true);
        this.name = name;
        this.project = project;
        this.pointer = pointer;
        this.results = results;
        this.afters = afters;
        this.selected = selected;
        this.comparison = comparison;
        this.before = before;
        this.currentText = currentText;
        this.besideName = besideName;
        setTitle(MessageBundle.message("convert.preview.title", results.get(selected).methodName()));
        updateOkText();
        init();
    }

    /** Opens the preview of {@code result}, once indexing is done; call on the EDT. */
    static void open(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiMethod> pointer,
                     @NotNull Converter.Result result, @NotNull String name) {
        open(project, pointer, List.of(result), 0, null, name);
    }

    /** Opens the preview of the kernels in {@code comparison}, with its recommendation selected. */
    static void open(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiMethod> pointer,
                     @NotNull Comparison comparison, @NotNull String name) {
        List<Converter.Result> results = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < comparison.entries().size(); i++) {
            Converter.Result result = comparison.entries().get(i).result();
            if (result != null) {
                if (i == comparison.chosen()) {
                    selected = results.size();
                }
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            TornadoNotifications.create(MessageBundle.message("convert.error.title", name),
                    comparison.failures().stream().map(StringUtil::escapeXmlEntities).collect(Collectors.joining("<br>")),
                    NotificationType.ERROR).notify(project);
            return;
        }
        open(project, pointer, results, selected, comparison, name);
    }

    /** The file as the dialog shows it, before and after each result in each placement. */
    private record Previews(List<Map<Placement, String>> afters, String before, String currentText, String besideName) {
    }

    private static void open(Project project, SmartPsiElementPointer<PsiMethod> pointer, List<Converter.Result> results,
                             int selected, @Nullable Comparison comparison, String name) {
        // Copying and reformatting the file for each preview is slow on a large file: it is done in
        // the background, as Converter checks the candidates, and the dialog opens when it is done
        ApplicationManager.getApplication().invokeLater(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            ReadAction.nonBlocking(() -> previews(pointer, results))
                    .inSmartMode(project)
                    .expireWith(project)
                    .finishOnUiThread(ModalityState.defaultModalityState(), previews -> {
                        if (previews == null) {
                            methodGone(project, name);
                        } else {
                            new ConversionPreviewDialog(project, pointer, results, previews.afters(), selected, comparison,
                                    previews.before(), previews.currentText(), previews.besideName(), name).show();
                        }
                    })
                    .submit(AppExecutorUtil.getAppExecutorService());
        }, project.getDisposed());
    }

    // null if the method is gone
    private static @Nullable Previews previews(SmartPsiElementPointer<PsiMethod> pointer, List<Converter.Result> results) {
        PsiMethod original = pointer.getElement();
        if (original == null) {
            return null;
        }
        List<Map<Placement, String>> afters = new ArrayList<>();
        for (Converter.Result result : results) {
            Map<Placement, String> after = new EnumMap<>(Placement.class);
            for (Placement placement : Placement.values()) {
                String text = KernelReplacement.preview(original, result.kernelText(), placement);
                if (text == null) {
                    return null;
                }
                after.put(placement, text);
            }
            afters.add(after);
        }
        return new Previews(afters, original.getContainingFile().getText(), original.getText(),
                KernelReplacement.besideName(original));
    }

    private Converter.Result result() {
        return results.get(selected);
    }

    private void updateOkText() {
        String key = placement == Placement.REPLACE ? "convert.preview.replace" : "convert.preview.addBeside";
        setOKButtonText(MessageBundle.message(result().problems().isEmpty() ? key : key + ".anyway"));
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), getWindow());
        summary = new JBLabel();
        summary.setAllowAutoWrapping(true);
        show(selected);

        JPanel top = new WrappingPanel();
        if (comparison != null) {
            top.add(new JBLabel("<html>" + comparison.table() + "</html>"), BorderLayout.NORTH);
        }
        if (results.size() > 1) {
            JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
            choices.add(new JBLabel(MessageBundle.message("convert.preview.choose")));
            ButtonGroup group = new ButtonGroup();
            for (int i = 0; i < results.size(); i++) {
                int index = i;
                JBRadioButton button = new JBRadioButton(results.get(i).target().displayName(), i == selected);
                button.addActionListener(e -> show(index));
                group.add(button);
                choices.add(button);
            }
            top.add(choices, BorderLayout.CENTER);
        }
        JPanel placing = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        placing.add(new JBLabel(MessageBundle.message("convert.preview.placement")));
        ButtonGroup placementGroup = new ButtonGroup();
        for (Placement option : Placement.values()) {
            JBRadioButton button = new JBRadioButton(option == Placement.BESIDE
                    ? MessageBundle.message("convert.preview.placement.beside", besideName)
                    : MessageBundle.message("convert.preview.placement.replace", result().methodName()), option == placement);
            button.addActionListener(e -> {
                placement = option;
                show(selected);
            });
            placementGroup.add(button);
            placing.add(button);
        }
        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        bottom.add(placing, BorderLayout.NORTH);
        bottom.add(summary, BorderLayout.CENTER);
        top.add(bottom, BorderLayout.SOUTH);

        // The text above the diff scrolls on its own, and the divider moves, so that a long summary
        // or timing table never pushes the dialog past the screen
        JBScrollPane scrolled = new JBScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrolled.setBorder(JBUI.Borders.empty());
        OnePixelSplitter panel = new OnePixelSplitter(true, comparison == null ? 0.3f : 0.4f);
        panel.setFirstComponent(scrolled);
        panel.setSecondComponent(diff.getComponent());
        panel.setPreferredSize(fitting(project, JBUI.size(900, comparison == null ? 700 : 800)));
        return panel;
    }

    // Smaller than the IDE window (or the screen, without one): at most 70% of its width, and 75% of
    // its height, which leaves room for the dialog's title bar and buttons
    static Dimension fitting(@NotNull Project project, @NotNull Dimension wanted) {
        Window frame = WindowManager.getInstance().getFrame(project);
        Rectangle bounds = frame != null && frame.isShowing() ? frame.getBounds() : ScreenUtil.getMainScreenBounds();
        return new Dimension(Math.min(wanted.width, bounds.width * 7 / 10), Math.min(wanted.height, bounds.height * 3 / 4));
    }

    private void show(int index) {
        selected = index;
        DiffContentFactory contents = DiffContentFactory.getInstance();
        diff.setRequest(new SimpleDiffRequest(null,
                contents.create(project, before, JavaFileType.INSTANCE),
                contents.create(project, afters.get(index).get(placement), JavaFileType.INSTANCE),
                MessageBundle.message("convert.preview.before"), MessageBundle.message("convert.preview.after")));
        updateSummary();
        updateOkText();
    }

    private void updateSummary() {
        Converter.Result result = result();
        boolean edited = !currentText.equals(result.methodText());
        StringBuilder html = new StringBuilder("<html>");
        html.append(MessageBundle.message("convert.preview.model", StringUtil.escapeXmlEntities(result.model()), result.rounds()));
        if (result.fellBack()) {
            html.append(' ').append(MessageBundle.message("convert.preview.fellBack"));
        }
        if (placement == Placement.BESIDE) {
            html.append("<br>").append(MessageBundle.message("convert.preview.beside", result.methodName(), besideName));
        } else if (result.target().context() != null) {
            html.append("<br><b>").append(MessageBundle.message("convert.preview.replaceContext", result.methodName(),
                    result.target().contextSimpleName())).append("</b>");
        } else if (result.callers() > 0) {
            html.append("<br>").append(MessageBundle.message("convert.preview.callers", result.callers()));
        }
        if (result.verified()) {
            html.append("<br>").append(MessageBundle.message("convert.preview.verified",
                    StringUtil.escapeXmlEntities(String.valueOf(result.outcome().shape()))));
        } else if (result.outcome() != null && result.outcome().status() == KernelVerifier.Status.NOT_VERIFIED) {
            html.append("<br>").append(MessageBundle.message("convert.preview.notVerified",
                    StringUtil.escapeXmlEntities(String.join(" ", result.outcome().details()))));
        }
        KernelGrid grid = result.target().context() == null ? null : KernelGrid.parse(result.kernelText());
        if (grid != null) {
            String local = grid.local().isEmpty() ? ""
                    : MessageBundle.message("convert.preview.launchLocal", StringUtil.escapeXmlEntities(String.join(", ", grid.local())));
            html.append("<br>").append(MessageBundle.message("convert.preview.launch",
                    StringUtil.escapeXmlEntities(grid.workerGrid()), local, result.target().contextSimpleName()));
        }
        if (result.repaired()) {
            html.append("<br>").append(MessageBundle.message("convert.preview.repaired"));
        }
        if (!result.explanation().isBlank()) {
            html.append("<br>").append(StringUtil.escapeXmlEntities(result.explanation()).replace("\n", "<br>"));
        }
        if (edited) {
            html.append("<br><br><b>").append(MessageBundle.message(placement == Placement.REPLACE
                    ? "convert.preview.edited" : "convert.preview.edited.beside")).append("</b>");
        }
        if (!result.problems().isEmpty()) {
            html.append("<br><br><b>").append(MessageBundle.message("convert.preview.problems", result.rounds())).append("</b><ul>");
            for (String problem : result.problems()) {
                html.append("<li>").append(StringUtil.escapeXmlEntities(problem)).append("</li>");
            }
            html.append("</ul>");
        }
        summary.setText(html.append("</html>").toString());
        summary.setForeground(edited && placement == Placement.REPLACE || !result.problems().isEmpty() ? UIUtil.getErrorForeground() : UIUtil.getLabelForeground());
    }

    @Override
    protected void doOKAction() {
        super.doOKAction();
        // The replacement resolves names, so it waits for indexing too
        String kernelText = result().kernelText();
        Placement chosen = placement;
        DumbService.getInstance(project).smartInvokeLater(() -> accept(project, pointer, kernelText, chosen, name));
    }

    private static void accept(Project project, SmartPsiElementPointer<PsiMethod> pointer, String kernelText,
                               Placement placement, String name) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        PsiMethod original = pointer.getElement();
        if (original == null) {
            methodGone(project, name);
            return;
        }
        KernelReplacement.Replaced[] replaced = new KernelReplacement.Replaced[1];
        WriteCommandAction.writeCommandAction(project, original.getContainingFile())
                .withName(MessageBundle.message("convert.command", original.getName()))
                .run(() -> replaced[0] = KernelReplacement.place(original, kernelText, placement));
        if (replaced[0] != null && replaced[0].kernel().canNavigate()) {
            replaced[0].kernel().navigate(true);
        }
    }

    // As wide as the scroll pane it is in, so that its text wraps instead of being cut off
    private static final class WrappingPanel extends JPanel implements Scrollable {
        WrappingPanel() {
            super(new BorderLayout(0, JBUI.scale(8)));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static void methodGone(Project project, String name) {
        TornadoNotifications.create(MessageBundle.message("convert.error.title", name),
                MessageBundle.message("convert.error.methodGone"), NotificationType.ERROR).notify(project);
    }
}
