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

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;

/**
 * Asks which API to convert a method to, or to convert it to each and keep the fastest, and
 * whether the kernels may be run on this machine to check them; the choice is remembered per
 * project.
 */
final class ConversionTargetDialog extends DialogWrapper {

    private static final String LAST_TARGET = "tornadoinsight.convert.target";
    private static final String LAST_LENGTH = "tornadoinsight.convert.benchmarkLength";
    private static final String LAST_THRESHOLD = "tornadoinsight.convert.threshold";
    private static final String LAST_RUN = "tornadoinsight.convert.run";
    private static final String FASTEST = "FASTEST";
    static final int DEFAULT_LENGTH = 1 << 20;
    // The harness holds several arrays of this length per parameter (inputs, copies, outputs) in
    // the heap of the tornado JVM: 64M floats are 256 MB each
    static final int MAX_LENGTH = 1 << 26;
    static final int DEFAULT_THRESHOLD = 20;
    static final int DESCRIPTION_WIDTH = 560;

    /**
     * What to convert to.
     *
     * @param target    the API, or {@code null} to convert to each and time them
     * @param length    for timing, the elements per array
     * @param threshold for timing, how much faster in percent a less readable kernel must be
     * @param run       whether the kernels the model writes may be run on this machine, to check
     *                  them against the method (always for the fastest)
     */
    record Choice(@Nullable Target target, int length, int threshold, boolean run) {
    }

    private final Project project;
    private final @Nullable String noTiming;
    private final Map<Target, String> unavailable;
    private final Map<Target, JBRadioButton> buttons = new EnumMap<>(Target.class);
    private final JBRadioButton fastest = new JBRadioButton(MessageBundle.message("convert.target.fastest.choice"));
    private final JBIntSpinner length;
    private final JBIntSpinner threshold;
    private final JBCheckBox run = new JBCheckBox(MessageBundle.message("convert.target.run"));

    /**
     * @param noTiming    why kernels cannot be timed (no TornadoVM), or {@code null}
     * @param unavailable why the method cannot be converted to a target, for the targets it cannot
     */
    private ConversionTargetDialog(@NotNull Project project, @NotNull String methodName, @Nullable String noTiming,
                                   @NotNull Map<Target, String> unavailable) {
        super(project, true);
        this.project = project;
        this.noTiming = noTiming;
        this.unavailable = unavailable;
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        length = new JBIntSpinner(Math.min(properties.getInt(LAST_LENGTH, DEFAULT_LENGTH), MAX_LENGTH), 1024, MAX_LENGTH, 1024);
        threshold = new JBIntSpinner(properties.getInt(LAST_THRESHOLD, DEFAULT_THRESHOLD), 0, 1000, 5);
        setTitle(MessageBundle.message("convert.target.title", methodName));
        setOKButtonText(MessageBundle.message("convert.target.convert"));
        init();
    }

    /** Asks what to convert to; {@code null} if the user cancels. Call on the EDT. */
    static @Nullable Choice choose(@NotNull Project project, @NotNull String methodName, @Nullable String noTiming,
                                   @NotNull Map<Target, String> unavailable) {
        ConversionTargetDialog dialog = new ConversionTargetDialog(project, methodName, noTiming, unavailable);
        if (!dialog.showAndGet()) {
            return null;
        }
        Choice choice = dialog.choice();
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        properties.setValue(LAST_TARGET, choice.target() == null ? FASTEST : choice.target().name());
        properties.setValue(LAST_LENGTH, choice.length(), DEFAULT_LENGTH);
        properties.setValue(LAST_THRESHOLD, choice.threshold(), DEFAULT_THRESHOLD);
        if (choice.target() != null) {
            // Fastest turns running on without asking; it is not the user's choice for the others
            properties.setValue(LAST_RUN, choice.run());
        }
        return choice;
    }

    private Choice choice() {
        Target target = null;
        if (!fastest.isSelected()) {
            target = Target.LOOP_PARALLEL;
            for (Map.Entry<Target, JBRadioButton> entry : buttons.entrySet()) {
                if (entry.getValue().isSelected()) {
                    target = entry.getKey();
                }
            }
        }
        return new Choice(target, length.getNumber(), threshold.getNumber(), target == null || run.isSelected());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        ButtonGroup group = new ButtonGroup();
        String last = PropertiesComponent.getInstance(project).getValue(LAST_TARGET, Target.LOOP_PARALLEL.name());
        if (last.equals(FASTEST) && noTiming != null) {
            last = Target.LOOP_PARALLEL.name();
        }
        for (Target target : Target.values()) {
            String why = unavailable.get(target);
            JBRadioButton button = new JBRadioButton(target.choiceLabel(), why == null && target.name().equals(last));
            button.setEnabled(why == null);
            buttons.put(target, button);
            add(panel, group, button, why == null ? target.choiceDescription()
                    : target.choiceDescription() + "<br>" + MessageBundle.message("convert.target.fastest.unavailable", why));
        }
        fastest.setSelected(last.equals(FASTEST));
        fastest.setEnabled(noTiming == null);
        // At most three answers for each API it converts to; the Tile API only on a CUDA device
        int apis = Target.values().length - unavailable.size();
        add(panel, group, fastest, noTiming == null ? MessageBundle.message("convert.target.fastest.description")
                + " " + MessageBundle.message("convert.target.fastest.cost", apis * Converter.MAX_ROUNDS, apis, Converter.MAX_ROUNDS)
                : MessageBundle.message("convert.target.fastest.unavailable", noTiming));
        if (buttons.values().stream().noneMatch(AbstractButton::isSelected) && !fastest.isSelected()) {
            buttons.get(Target.LOOP_PARALLEL).setSelected(true);
        }

        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = JBUI.insets(2, 24, 2, 4);
        c.gridy = 0;
        options.add(new JBLabel(MessageBundle.message("convert.target.fastest.length")), c);
        options.add(length, c);
        c.gridy = 1;
        options.add(new JBLabel(MessageBundle.message("convert.target.fastest.threshold")), c);
        options.add(threshold, c);
        options.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(options);

        // Off until the user turns it on: the model's code, and the method, run with the user's rights
        boolean[] runChosen = {PropertiesComponent.getInstance(project).getBoolean(LAST_RUN, false)};
        run.addItemListener(e -> {
            if (run.isEnabled()) {
                runChosen[0] = run.isSelected();
            }
        });
        run.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(JBUI.scale(8)));
        panel.add(run);
        panel.add(description(noTiming == null ? MessageBundle.message("convert.target.run.description")
                : MessageBundle.message("convert.target.fastest.unavailable", noTiming)));
        Runnable enable = () -> {
            length.setEnabled(fastest.isSelected());
            threshold.setEnabled(fastest.isSelected());
            // Fastest times the kernels, so it runs them
            run.setEnabled(noTiming == null && !fastest.isSelected());
            run.setSelected(noTiming == null && (fastest.isSelected() || runChosen[0]));
        };
        fastest.addItemListener(e -> enable.run());
        enable.run();
        panel.setBorder(JBUI.Borders.empty(4));

        // Scrolls instead of growing past the IDE window, on a small screen or with a large font; the
        // vertical scroll bar's width is kept free so that it never needs a horizontal one
        JBScrollPane scrolled = new JBScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrolled.setBorder(JBUI.Borders.empty());
        Dimension wanted = panel.getPreferredSize();
        scrolled.setPreferredSize(ConversionPreviewDialog.fitting(project,
                new Dimension(wanted.width + scrolled.getVerticalScrollBar().getPreferredSize().width, wanted.height)));
        return scrolled;
    }

    private static void add(JPanel panel, ButtonGroup group, JBRadioButton button, String description) {
        group.add(button);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(button);
        panel.add(description(description));
        panel.add(Box.createVerticalStrut(JBUI.scale(8)));
    }

    // Wrapped at a fixed width: unwrapped, the longest description set the dialog's width. Swing's
    // HTML takes a CSS px as a point, 4/3 of a pixel
    static JBLabel description(String description) {
        int width = JBUI.scale(DESCRIPTION_WIDTH) * 3 / 4;
        JBLabel text = new JBLabel("<html><body style='width: " + width + "px'>" + description + "</body></html>");
        text.setForeground(UIUtil.getContextHelpForeground());
        text.setBorder(JBUI.Borders.emptyLeft(24));
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        return text;
    }
}
