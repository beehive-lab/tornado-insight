package com.tais.tornado_plugins.util;

import java.util.regex.Pattern;

/**
 * Provides utility methods to validate various primitive types and their array counterparts
 * from their string representations.
 */
public class InputValidation {

    /**
     * Validates if the given string can be parsed as an integer.
     *
     * @param str The input string.
     * @return True if the string represents an integer, otherwise false.
     */
    public static boolean isInteger(String str) {
        Pattern pattern = Pattern.compile("^-?\\d*$");
        return pattern.matcher(str).matches();
    }
    public static boolean isFloat(String str) {
        Pattern pattern = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+[fF]?");
        return pattern.matcher(str).matches();
    }

    public static boolean isShort(String str) {
        Pattern pattern = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+");
        return pattern.matcher(str).matches() &&
                Short.MIN_VALUE <= Integer.parseInt(str) &&
                Integer.parseInt(str) <= Short.MAX_VALUE;
    }

    public static boolean isByte(String str) {
        Pattern pattern = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+");
        return pattern.matcher(str).matches() &&
                Byte.MIN_VALUE <= Integer.parseInt(str) &&
                Integer.parseInt(str) <= Byte.MAX_VALUE;

    }

    public static boolean isChar(String str) {
        Pattern pattern = Pattern.compile("^.$");
        return pattern.matcher(str).matches();
    }

    public static boolean isDouble(String str) {
        return isFloat(str);
    }

    public static boolean isLong(String str) {
        return isInteger(str);
    }

    public static boolean isBoolean(String str) {
        return str.equalsIgnoreCase("true") || str.equalsIgnoreCase("false");
    }

    /**
     * Validates if the given string represents an array of integers.
     * An example of valid string is "{1, 2, 3}".
     *
     * @param str The input string.
     * @return True if the string represents an array of integers, otherwise false.
     */
    public static boolean isIntArray(String str) {
        if (str.startsWith("{") && str.endsWith("}")) {
            String[] parts = str.substring(1, str.length() - 1).split(",");
            // Check each element
            for (String part : parts) {
                if (!isInteger(part.trim())) return false;
            }
            return true;
        } else return false;
    }

    public static boolean isShortArray(String str) {
        if (str.startsWith("{") && str.endsWith("}")) {
            String[] parts = str.substring(1, str.length() - 1).split(",");
            for (String part : parts) {
                if (!isShort(part.trim())) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static boolean isLongArray(String str) {
        return isIntArray(str);
    }

    public static boolean isByteArray(String str) {
        if (str.startsWith("{") && str.endsWith("}")) {
            String[] parts = str.substring(1, str.length() - 1).split(",");
            for (String part : parts) {
                if (!isByte(part.trim())) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static boolean isFloatArray(String str) {
        if (str.startsWith("{") && str.endsWith("}")) {
            String[] parts = str.substring(1, str.length() - 1).split(",");
            for (String part : parts) {
                if (!isFloat(part.trim())) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static boolean isDoubleArray(String str) {
        return isFloatArray(str);
    }

    public static boolean isCharArray(String str) {
        if (str.startsWith("{") && str.endsWith("}")) {
            String[] parts = str.substring(1, str.length() - 1).split(",");
            for (String part : parts) {
                if (!isChar(part.trim())) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static boolean isBooleanArray(String str) {
        if (str.startsWith("{") && str.endsWith("}")) {
            String[] parts = str.substring(1, str.length() - 1).split(",");
            for (String part : parts) {
                if (!isBoolean(part.trim())) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }
}
