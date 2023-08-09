package com.tais.tornado_plugins.ui;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBList;
import com.tais.tornado_plugins.service.TWTasksButtonEvent;

import javax.swing.*;

import static javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION;

public class TornadoVM {
    private JTabbedPane tabbedPanel;
    private JPanel mainPanel;
    private JPanel taskPanel;
    private JBList tasksList;
    private JCheckBox inspector1;
    private JButton applyButton;
    private JScrollPane scrollPane1;
    private JPanel settingsPanel;
    private JPanel inspectorPanel;
    private JButton inspectionApply;
    private JList inspectorList;
    private JButton button1;
    private JScrollPane JscrollPane1;

    public TornadoVM(ToolWindow toolWindow) {
        TWTasksButtonEvent service = new TWTasksButtonEvent();
        DefaultListModel defaultListModel = new DefaultListModel();
        tasksList.setModel(defaultListModel);
        tasksList.getEmptyText().setText("No TornadoVM task detected");
        tasksList.setSelectionMode(MULTIPLE_INTERVAL_SELECTION);
        button1.setText("Applying TornadoVM Dynamic Inspection");
        button1.addActionListener(e -> service.pressButton());

        DefaultListModel inspectorListModel = new DefaultListModel<>();
        inspectorList.setModel(inspectorListModel);
    }

    public JTabbedPane getTabbedPanel() {
        return tabbedPanel;
    }

    public JPanel getTaskPanel() {
        return taskPanel;
    }

    public JScrollPane getScrollPane1() {
        return scrollPane1;
    }

    public JPanel getSettingsPanel() {
        return settingsPanel;
    }

    public JPanel getInspectorPanel() {
        return inspectorPanel;
    }

    public JButton getInspectionApply() {
        return inspectionApply;
    }

    public JList getInspectorList() {
        return inspectorList;
    }

    public DefaultListModel getModel() {
        return (DefaultListModel) tasksList.getModel();
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }

    public JTabbedPane getTabbedPane1() {
        return tabbedPanel;
    }

    public JPanel getTask() {
        return taskPanel;
    }

    public JList getTasksList() {
        return tasksList;
    }

    public JCheckBox getInspector1() {
        return inspector1;
    }

    public JButton getApplyButton() {
        return applyButton;
    }

}
