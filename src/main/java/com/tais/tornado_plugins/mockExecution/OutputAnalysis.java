package com.tais.tornado_plugins.mockExecution;

import com.intellij.execution.process.ProcessOutput;

import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OutputAnalysis {
    static final String defaultMessage = "There is an exception in this method, " +
            "but TornadoInsight is unable to analyse the cause at the moment";

    //This method is used to analyse the output of Tornado where an exception exists
    public static String analysis(ProcessOutput output) {
        String outputText = output.toString();

        if (outputText.contains(" dynamically sized array declarations are not supported")){
            return  "This method has dynamic memory allocation caused by dynamically sized array declarations , " +
                    "which is not supported by TornadoVM.";
        }
        else if (outputText.contains("Unable to build sketch for method")){
            return getMethodName(outputText);
        }
        return defaultMessage;
    }

    private static String getMethodName(String outputText) {
        Pattern pattern = Pattern.compile("Unable to build sketch for method: ([^\\(]+)");
        Matcher matcher = pattern.matcher(outputText);
        HashSet<String> result = new HashSet<>();
        StringBuilder resultString = new StringBuilder();
        while (matcher.find()) {
            result.add(matcher.group(1).trim());
        }
        if (result.isEmpty()) {
            return defaultMessage;
        }
        for (String s : result){
            resultString.append(s).append(", ");
        }
        return "The method: " + result + " is not supported by TornadoVM.";
    }
}
