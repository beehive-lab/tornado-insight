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

package uk.ac.manchester.beehive.tornado.plugins.settings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.ai.ClaudeProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException;
import uk.ac.manchester.beehive.tornado.plugins.ai.ModelInfo;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The form of <em>Settings | Other Settings | TornadoInsight | AI-Assisted Transformation</em>.
 */
public class AiSettingsComponent {

    static final List<String> CLAUDE_MODELS = List.of(ClaudeProvider.DEFAULT_MODEL, "claude-sonnet-5-5", "claude-haiku-5-5");

    private final JPanel panel;
    private final JBCheckBox enabled = new JBCheckBox(MessageBundle.message("ai.settings.enabled"));
    private final JBRadioButton local = new JBRadioButton(MessageBundle.message("ai.settings.provider.local"));
    private final JBRadioButton claude = new JBRadioButton(MessageBundle.message("ai.settings.provider.claude"));
    private final JBTextField localUrl = new JBTextField();
    private final JBPasswordField localKey = new JBPasswordField();
    private final ComboBox<String> localModel = new ComboBox<>();
    private final JBIntSpinner localMaxTokens = new JBIntSpinner(AiSettingState.DEFAULT_LOCAL_MAX_TOKENS,
            AiSettingState.MIN_LOCAL_MAX_TOKENS, AiSettingState.MAX_LOCAL_MAX_TOKENS, 1024);
    private final JBPasswordField claudeKey = new JBPasswordField();
    private final ComboBox<String> claudeModel = new ComboBox<>(CLAUDE_MODELS.toArray(String[]::new));
    private final ComboBox<ClaudeProvider.Effort> claudeEffort = new ComboBox<>(ClaudeProvider.Effort.values());
    private final JButton testConnection = new JButton(MessageBundle.message("ai.settings.testConnection"));
    private final JBLabel testResult = new JBLabel();
    private final JBLabel unencrypted = new JBLabel(MessageBundle.message("ai.settings.local.url.unencrypted"),
            AllIcons.General.Warning, SwingConstants.LEADING);
    private final JPanel localPanel;
    private final JPanel claudePanel;

    public AiSettingsComponent() {
        ButtonGroup providers = new ButtonGroup();
        providers.add(local);
        providers.add(claude);
        localModel.setEditable(true);
        claudeModel.setEditable(true);

        localPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(MessageBundle.message("ai.settings.local.url"), localUrl)
                .addComponentToRightColumn(unencrypted)
                .addComponentToRightColumn(hint(MessageBundle.message("ai.settings.local.url.hint")))
                .addLabeledComponent(MessageBundle.message("ai.settings.local.model"), localModel)
                .addComponentToRightColumn(hint(MessageBundle.message("ai.settings.local.model.hint")))
                .addLabeledComponent(MessageBundle.message("ai.settings.local.key"), localKey)
                .addLabeledComponent(MessageBundle.message("ai.settings.local.maxTokens"), localMaxTokens)
                .addComponentToRightColumn(hint(MessageBundle.message("ai.settings.local.maxTokens.hint")))
                .getPanel();
        localPanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ai.settings.provider.local")));

        String environmentHint = AiCredentials.claudeKeyFromEnvironment() == null
                ? MessageBundle.message("ai.settings.claude.key.hint", AiCredentials.CLAUDE_KEY_VARIABLE)
                : MessageBundle.message("ai.settings.claude.key.environment", AiCredentials.CLAUDE_KEY_VARIABLE);
        claudePanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(MessageBundle.message("ai.settings.claude.key"), claudeKey)
                .addComponentToRightColumn(hint(environmentHint))
                .addLabeledComponent(MessageBundle.message("ai.settings.claude.model"), claudeModel)
                .addLabeledComponent(MessageBundle.message("ai.settings.claude.effort"), claudeEffort)
                .getPanel();
        claudePanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ai.settings.provider.claude")));

        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testRow.add(testConnection);
        testRow.add(Box.createHorizontalStrut(8));
        testRow.add(testResult);

        panel = FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addComponent(hint(MessageBundle.message("ai.settings.privacy")))
                .addLabeledComponent(MessageBundle.message("ai.settings.provider"), providerRow())
                .addComponent(localPanel)
                .addComponent(claudePanel)
                .addComponent(testRow)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        enabled.addActionListener(e -> updateEnabled());
        local.addActionListener(e -> updateEnabled());
        claude.addActionListener(e -> updateEnabled());
        testConnection.addActionListener(e -> testConnection());
        localUrl.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                unencrypted.setVisible(AiProviders.isUnencryptedRemote(localUrl.getText()));
            }
        });
        unencrypted.setVisible(false);
    }

    public @NotNull JPanel getPanel() {
        return panel;
    }

    /** Shows {@code settings}, with {@code localKey} and {@code claudeKey} as the stored keys. */
    public void reset(@NotNull AiSettingState settings, @NotNull String localKey, @NotNull String claudeKey) {
        enabled.setSelected(settings.enabled);
        (settings.provider == AiSettingState.Provider.CLAUDE ? claude : local).setSelected(true);
        localUrl.setText(settings.localUrl);
        localModel.setSelectedItem(settings.localModel);
        localMaxTokens.setNumber(settings.localMaxTokens());
        claudeModel.setSelectedItem(settings.claudeModel);
        claudeEffort.setSelectedItem(settings.claudeEffort);
        this.localKey.setText(localKey);
        this.claudeKey.setText(claudeKey);
        testResult.setText("");
        testResult.setIcon(null);
        updateEnabled();
    }

    /** Shows the stored keys, once they are read. */
    public void showKeys(@NotNull String localKey, @NotNull String claudeKey) {
        this.localKey.setText(localKey);
        this.claudeKey.setText(claudeKey);
    }

    /** The settings as the form shows them. */
    public @NotNull AiSettingState toState() {
        AiSettingState state = new AiSettingState();
        state.enabled = enabled.isSelected();
        state.provider = claude.isSelected() ? AiSettingState.Provider.CLAUDE : AiSettingState.Provider.LOCAL;
        state.localUrl = localUrl.getText().strip();
        state.localModel = text(localModel);
        state.localMaxTokens = localMaxTokens.getNumber();
        state.claudeModel = text(claudeModel).isEmpty() ? ClaudeProvider.DEFAULT_MODEL : text(claudeModel);
        state.claudeEffort = (ClaudeProvider.Effort) Objects.requireNonNullElse(claudeEffort.getSelectedItem(), ClaudeProvider.Effort.HIGH);
        return state;
    }

    public @NotNull String localKey() {
        return new String(localKey.getPassword());
    }

    public @NotNull String claudeKey() {
        return new String(claudeKey.getPassword());
    }

    /** Why the form cannot be saved, or {@code null}. */
    public @Nullable String validate() {
        AiSettingState state = toState();
        if (!state.enabled) {
            return null;
        }
        return state.provider == AiSettingState.Provider.LOCAL
                ? AiProviders.urlError(state.localUrl) : AiProviders.claudeModelError(state.claudeModel);
    }

    /** The text Test connection shows for a server that answered with {@code info}. */
    static @NotNull String connected(@NotNull AiSettingState.Provider provider, @NotNull ModelInfo info) {
        String models = String.join(", ", info.models());
        if (info.contextLength() == null) {
            return MessageBundle.message("ai.settings.test.ok", models);
        }
        return MessageBundle.message(provider == AiSettingState.Provider.CLAUDE ? "ai.settings.test.ok.claude" : "ai.settings.test.ok.context",
                models, String.format(Locale.ROOT, "%,d", info.contextLength()));
    }

    private void testConnection() {
        AiSettingState state = toState();
        String key = state.provider == AiSettingState.Provider.CLAUDE ? claudeKey() : localKey();
        try {
            ModelInfo info = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
                        return AiProviders.create(state, key).describe(() -> indicator != null && indicator.isCanceled());
                    },
                    MessageBundle.message("ai.settings.test.progress"), true, null);
            if (state.provider == AiSettingState.Provider.LOCAL) {
                String selected = text(localModel);
                localModel.removeAllItems();
                info.models().forEach(localModel::addItem);
                localModel.setSelectedItem(selected);
            }
            show(true, connected(state.provider, info));
        } catch (LlmException e) {
            show(false, e.getMessage());
        }
    }

    private void show(boolean ok, String message) {
        testResult.setIcon(ok ? AllIcons.General.InspectionsOK : AllIcons.General.Error);
        testResult.setText(message);
    }

    private JPanel providerRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(local);
        row.add(Box.createHorizontalStrut(12));
        row.add(claude);
        return row;
    }

    private void updateEnabled() {
        boolean on = enabled.isSelected();
        local.setEnabled(on);
        claude.setEnabled(on);
        setEnabled(localPanel, on && local.isSelected());
        setEnabled(claudePanel, on && claude.isSelected());
        testConnection.setEnabled(on);
    }

    private static void setEnabled(Container container, boolean enabled) {
        container.setEnabled(enabled);
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                setEnabled(nested, enabled);
            } else {
                child.setEnabled(enabled);
            }
        }
    }

    private static JComponent hint(String text) {
        JBLabel label = new JBLabel("<html><div style='width:400px'>" + text + "</div></html>");
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    private static String text(ComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString().strip();
    }
}
