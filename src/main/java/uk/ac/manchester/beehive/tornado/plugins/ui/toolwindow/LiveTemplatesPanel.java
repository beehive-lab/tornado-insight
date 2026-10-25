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

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import uk.ac.manchester.beehive.tornado.plugins.templates.TornadoVMTemplates;
import uk.ac.manchester.beehive.tornado.plugins.templates.TornadoVMTemplates.Template;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The Live Templates tab: a table of the TornadoVM live templates (abbreviation, description and
 * where in the code each is offered) and a preview of the code the selected one inserts.
 */
final class LiveTemplatesPanel extends SimpleToolWindowPanel {

    LiveTemplatesPanel() {
        super(Boolean.TRUE, Boolean.TRUE);
        List<Template> templates = TornadoVMTemplates.all();

        JBTable table = new JBTable(new TemplatesModel(templates));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(90));
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(120));

        JBTextArea preview = new JBTextArea();
        preview.setEditable(false);
        preview.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        preview.setBorder(JBUI.Borders.empty(6, 8));
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            preview.setText(row < 0 ? "" : templates.get(table.convertRowIndexToModel(row)).preview());
            preview.setCaretPosition(0);
        });

        JBLabel hint = new JBLabel(MessageBundle.message("ui.toolwindow.templates.hint"));
        hint.setBorder(JBUI.Borders.empty(6, 8));

        JBSplitter splitter = new JBSplitter(true, 0.4f);
        splitter.setFirstComponent(new JBScrollPane(table));
        splitter.setSecondComponent(new JBScrollPane(preview));

        JPanel content = new JPanel(new BorderLayout());
        content.add(hint, BorderLayout.NORTH);
        content.add(splitter, BorderLayout.CENTER);
        setContent(content);

        if (!templates.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private static final class TemplatesModel extends AbstractTableModel {

        private static final String[] COLUMNS = { "ui.toolwindow.templates.column.abbreviation", "ui.toolwindow.templates.column.description",
                "ui.toolwindow.templates.column.where" };

        private final List<Template> templates;

        TemplatesModel(List<Template> templates) {
            this.templates = templates;
        }

        @Override
        public int getRowCount() {
            return templates.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return MessageBundle.message(COLUMNS[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Template template = templates.get(row);
            return switch (column) {
                case 0 -> template.abbreviation();
                case 1 -> template.description();
                default -> template.contexts().stream().map(id -> MessageBundle.message("templates.context." + id)).collect(Collectors.joining("; "));
            };
        }
    }
}
