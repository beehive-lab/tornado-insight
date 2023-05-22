package com.tais.tornado_plugins;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

public class TornadoDataTypeLoad implements ProjectManagerListener {
    @Override
    public void projectOpened(@NotNull Project project) {
        Properties properties = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream("../resources/conf.properties");
            properties.load(in);

            in.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
