package uk.ac.manchester.beehive.tornado.plugins.entity;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EnvironmentVariable {
    private static String javaHome;
    private static String path;
    private static String cmakeRoot;
    private static String tornadoSdk;

    private EnvironmentVariable() {
    }

    public static void parseFile(String filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("export ")) {
                    parseExportLine(line);
                }
            }
        }
    }
    private static void parseExportLine(String line) {
        String javaHomePattern = "export\\s+JAVA_HOME=(\\S+)";
        String pathPattern = "export\\s+PATH=(\\S+)";
        String cmakeRootPattern = "export\\s+CMAKE_ROOT=(\\S+)";
        String tornadoSdkPattern = "export\\s+TORNADO_SDK=(\\S+)";

        if (matchPattern(line, javaHomePattern)) {
            javaHome = extractValue(line);
        } else if (matchPattern(line, pathPattern)) {
            path = extractValue(line);
        } else if (matchPattern(line, cmakeRootPattern)) {
            cmakeRoot = extractValue(line);
        } else if (matchPattern(line, tornadoSdkPattern)) {
            tornadoSdk = extractValue(line);
        }
    }

    private static boolean matchPattern(String line, String pattern) {
        Pattern r = Pattern.compile(pattern);
        Matcher matcher = r.matcher(line);
        return matcher.find();
    }

    private static String extractValue(String line) {
        return line.split("=")[1].trim().replaceAll("\"", "");
    }

    public static String getJavaHome() {
        return javaHome;
    }

    public static String getPath() {
        return path;
    }

    public static String getCmakeRoot() {
        return cmakeRoot;
    }

    public static String getTornadoSdk() {
        return tornadoSdk;
    }
}
