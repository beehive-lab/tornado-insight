package com.tais.tornado_plugins.mockExecution;

import com.intellij.execution.process.ProcessOutput;

public class OutputAnalysis {

    //This method is used to analyse the output of Tornado where an exception exists
    public static String analysis(ProcessOutput output) {
        String result = "There is an exception in your method.\n";
        return result + output.getStderr();
    }
}
