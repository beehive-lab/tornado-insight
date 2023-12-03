package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.DataKey;

import javax.swing.*;
import java.util.List;

public class DataKeys {
    public static final DataKey<ConsoleView> TORNADO_CONSOLE_VIEW = DataKey.create("TORNADO_CONSOLE_VIEW");
    public static final DataKey<ListModel<String>> TORNADOINSIGHT_LIST_MODEL = DataKey.create("TORNADOINSIGHT_LIST_MODEL");
    public static final DataKey<List<String>> TORNADO_SELECTED_LIST = DataKey.create("TORNADO_SELECTED_LIST");

    public static final DataKey<JList<String>> TORNADO_LIST = DataKey.create("TORNADO_LIST");
}
