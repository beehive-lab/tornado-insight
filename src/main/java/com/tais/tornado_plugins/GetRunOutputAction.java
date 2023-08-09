package com.tais.tornado_plugins;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

public class GetRunOutputAction implements ExecutionListener {

    @Override
    public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        System.out.println("Process Started");
        handler.addProcessListener(new ProcessAdapter(){
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (outputType != ProcessOutputTypes.STDERR) return;
                //Get the error output
                String text = event.getText();
                super.onTextAvailable(event, outputType);
            }
        });
    }
}
