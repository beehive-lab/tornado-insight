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

package uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow;

import com.intellij.ide.DataManager;
import com.intellij.ide.impl.HeadlessDataManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.UIUtil;
import uk.ac.manchester.beehive.tornado.plugins.util.DataKeys;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTaskService.TaskEntry;

import javax.swing.*;
import java.util.List;

public class TornadoSideWindowTest extends BasePlatformTestCase {

    @SuppressWarnings("unchecked")
    public void testSelectedTasksReachTheActionsDataContext() {
        PsiFile file = myFixture.configureByText("Demo.java", "class Demo { void kernel() {} }");
        PsiMethod method = PsiTreeUtil.findChildOfType(file, PsiMethod.class);
        TaskEntry entry = new TaskEntry("kernel()",
                SmartPointerManager.getInstance(getProject()).createSmartPsiElementPointer(method));

        ToolWindow toolWindow = new ToolWindowHeadlessManagerImpl.MockToolWindow(getProject());
        new TornadoSideWindow().createToolWindowContent(getProject(), toolWindow);
        JComponent tasksTab = toolWindow.getContentManager().getContent(0).getComponent();
        JBList<TaskEntry> list = UIUtil.findComponentOfType(tasksTab, JBList.class);
        assertNotNull(list);
        ((DefaultListModel<TaskEntry>) list.getModel()).addElement(entry);
        list.setSelectedIndex(0);

        // Resolve the data through the component hierarchy, as for a toolbar action
        HeadlessDataManager.fallbackToProductionDataManager(getTestRootDisposable());
        List<TaskEntry> selected = DataManager.getInstance().getDataContext(list).getData(DataKeys.TORNADO_SELECTED_LIST);
        assertEquals(List.of(entry), selected);
    }
}
