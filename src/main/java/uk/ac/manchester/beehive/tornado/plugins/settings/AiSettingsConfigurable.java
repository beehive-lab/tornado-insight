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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import java.util.Objects;

/**
 * <em>Settings | Other Settings | TornadoInsight | AI-Assisted Transformation</em>.
 */
public class AiSettingsConfigurable implements Configurable {

    private AiSettingsComponent component;
    // The keys as stored when the page opened, so isModified does not read the password store
    private String storedLocalKey = "";
    private String storedClaudeKey = "";
    // Counts the resets and applies, so that keys read for an older one are not shown
    private int generation;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return MessageBundle.message("ai.settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        component = new AiSettingsComponent();
        return component.getPanel();
    }

    @Override
    public boolean isModified() {
        AiSettingState saved = AiSettingState.getInstance();
        AiSettingState shown = component.toState();
        return saved.enabled != shown.enabled
                || saved.provider != shown.provider
                || !Objects.equals(saved.localUrl, shown.localUrl)
                || !Objects.equals(saved.localModel, shown.localModel)
                || saved.localMaxTokens() != shown.localMaxTokens
                || !Objects.equals(saved.claudeModel, shown.claudeModel)
                || saved.claudeEffort != shown.claudeEffort
                || !storedLocalKey.equals(component.localKey())
                || !storedClaudeKey.equals(component.claudeKey());
    }

    @Override
    public void apply() throws ConfigurationException {
        String error = component.validate();
        if (error != null) {
            throw new ConfigurationException(error);
        }
        AiSettingState.getInstance().loadState(component.toState());
        generation++;
        if (!storedLocalKey.equals(component.localKey())) {
            AiCredentials.store(AiSettingState.Provider.LOCAL, component.localKey());
            storedLocalKey = component.localKey();
        }
        if (!storedClaudeKey.equals(component.claudeKey())) {
            AiCredentials.store(AiSettingState.Provider.CLAUDE, component.claudeKey());
            storedClaudeKey = component.claudeKey();
        }
    }

    @Override
    public void reset() {
        int reset = ++generation;
        AiSettingsComponent shown = component;
        String oldLocalKey = storedLocalKey;
        String oldClaudeKey = storedClaudeKey;
        component.reset(AiSettingState.getInstance(), oldLocalKey, oldClaudeKey);
        // The password store may be slow (a keychain, KeePass), so it is read in the background
        ModalityState modality = ModalityState.stateForComponent(shown.getPanel());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String localKey = AiCredentials.stored(AiSettingState.Provider.LOCAL);
            String claudeKey = AiCredentials.stored(AiSettingState.Provider.CLAUDE);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (component != shown || generation != reset) {
                    return;
                }
                storedLocalKey = localKey;
                storedClaudeKey = claudeKey;
                // A key the user typed meanwhile stays
                shown.showKeys(shown.localKey().equals(oldLocalKey) ? localKey : shown.localKey(),
                        shown.claudeKey().equals(oldClaudeKey) ? claudeKey : shown.claudeKey());
            }, modality);
        });
    }

    @Override
    public void disposeUIResources() {
        component = null;
    }
}
