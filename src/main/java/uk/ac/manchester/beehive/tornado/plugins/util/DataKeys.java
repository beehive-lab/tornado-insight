/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
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
