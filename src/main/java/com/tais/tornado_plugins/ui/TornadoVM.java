package com.tais.tornado_plugins.ui;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBList;
import com.tais.tornado_plugins.service.TWTasksButtonEvent;

import javax.swing.*;

import static javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION;

/**
 * Represents the main UI component for interacting with TornadoVM toolwindow.
 * <p>
 * This class provides the UI elements required to manage TornadoVM related tasks
 * within the IntelliJ IDEA environment, including Task listings and inspectors
 * </p>
 */
public class TornadoVM {

    // UI components
    private JTabbedPane tabbedPanel;
    private JPanel mainPanel;
    private JPanel taskPanel;
    private JBList tasksList;
    private JPanel inspectorPanel;
    private JButton inspectionApply;
    private JList inspectorList;
    private JButton button1;
    private JScrollPane JscrollPane1;

    /**
     * Constructs a new TornadoVM toolwindow UI instance and initializes its components.
     *
     * @param toolWindow the IntelliJ tool window
     */
    public TornadoVM(ToolWindow toolWindow) {
        TWTasksButtonEvent service = new TWTasksButtonEvent();
        DefaultListModel<String> defaultListModel = new DefaultListModel<>();
        tasksList.setModel(defaultListModel);
        tasksList.getEmptyText().setText("No TornadoVM task detected");
        tasksList.setSelectionMode(MULTIPLE_INTERVAL_SELECTION);
        button1.setText("Applying TornadoVM Dynamic Inspection");
        button1.addActionListener(e -> service.pressButton());

        DefaultListModel<String> inspectorListModel = new DefaultListModel<>();
        inspectorList.setModel(inspectorListModel);
    }

    public JList getInspectorList() {
        return inspectorList;
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }

    public JList getTasksList() {
        return tasksList;
    }

}
