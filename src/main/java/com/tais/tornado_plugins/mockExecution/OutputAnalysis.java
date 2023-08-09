package com.tais.tornado_plugins.mockExecution;

import com.intellij.execution.process.ProcessOutput;

public class OutputAnalysis {

    //This method is used to analyse the output of Tornado where an exception exists
    public static String analysis(ProcessOutput output) {
        String outputText = output.toString();
        if (outputText.contains(" dynamically sized array declarations are not supported")){
            return  "This method has dynamic memory allocation, which is not supported by TornadoVM.";
        }
        return "There is an exception in this method, " +
                "but TornadoInsight is unable to analyse the cause at the moment";
    }
}
