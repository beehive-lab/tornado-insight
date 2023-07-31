package com.tais.tornado_plugins.fileWriter;

import com.intellij.openapi.util.io.FileUtilRt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class WriteCode {
    static File file;

    static {
        try {
            file = FileUtilRt.createTempFile("testCode",".java",true) ;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void bufferedWriterMethod(String content) throws IOException {
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(file))) {
            System.out.println("Done"+ file.getPath());
            bufferedWriter.write(content);
        }
    }

}
