package com.tais.tornado_plugins.ui;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.HideableTitledPanel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.tais.tornado_plugins.entity.InspectionsContainer;
import com.tais.tornado_plugins.inspector.DataTypeInspection;

import javax.swing.*;
import java.io.InputStream;
import java.io.InputStreamReader;

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
    private JList inspectorList;
    private JButton button1;
    private JScrollPane JscrollPane1;
    private JScrollPane InspectorScollPane;
    private InspectionsContainer inspectionsContainer;

    /**
     * Constructs a new TornadoVM toolwindow UI instance and initializes its components.
     *
     * @param toolWindow the IntelliJ tool window
     */
    public TornadoVM(ToolWindow toolWindow) {
        DefaultListModel<String> defaultListModel = new DefaultListModel<>();
        tasksList.setModel(defaultListModel);
        tasksList.getEmptyText().setText("No TornadoVM task detected");
        tasksList.setSelectionMode(MULTIPLE_INTERVAL_SELECTION);
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

    public JScrollPane getInspectorScollPane() {
        return InspectorScollPane;
    }

    private void createUIComponents() {
        Gson gson = new Gson();
        InputStream resource = DataTypeInspection.class.getClassLoader().getResourceAsStream("inspectors.json");
        assert resource != null;
        JsonReader reader = new JsonReader(new InputStreamReader(resource));
        inspectionsContainer = gson.fromJson(reader, InspectionsContainer.class);
        JPanel inspectionPane = new JBPanel<>();
        inspectionPane.setLayout(new BoxLayout(inspectionPane,BoxLayout.Y_AXIS));
        for (InspectionsContainer.Inspector inspector : inspectionsContainer.getInspections()) {
            HideableTitledPanel p = new HideableTitledPanel(inspector.getName());
            JBLabel label = new JBLabel(inspector.getDescription());
            label.setAllowAutoWrapping(true);
            p.setContentComponent(label);
            inspectionPane.add(p);
        }

        InspectorScollPane = new JBScrollPane(InpectorInfoKt.inspectorPane());
    }

    private static JPanel createCollapsibleCard(String title, String content) {
        JPanel cardPanel = new JPanel();
        cardPanel.setLayout(new BoxLayout(cardPanel,BoxLayout.Y_AXIS));
        JButton toggleButton = new JButton(title);
        JTextArea contentArea = new JTextArea(content);
        contentArea.setAlignmentX(-0.5f);
        contentArea.setWrapStyleWord(true);
        contentArea.setLineWrap(true);
        contentArea.setVisible(false);

        toggleButton.addActionListener(e -> {
            contentArea.setVisible(!contentArea.isVisible());
            cardPanel.revalidate();
            cardPanel.repaint();
        });

        cardPanel.add(toggleButton);
        cardPanel.add(contentArea);

        return cardPanel;
    }
}
