package com.denorite;

public class CommandHelper {
    /**
     * Escapes and formats a command string to properly handle NBT data
     * @param command The raw command string
     * @return Properly escaped command string
     */
    public static String escapeCommand(String command) {
        // First, identify NBT data sections (content within curly braces)
        StringBuilder escaped = new StringBuilder();
        boolean inNBT = false;
        boolean inQuote = false;
        char[] chars = command.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (c == '{') {
                inNBT = true;
                escaped.append(c);
            } else if (c == '}') {
                inNBT = false;
                escaped.append(c);
            } else if (c == '\'' && inNBT) {
                // Replace single quotes with escaped quotes in NBT data
                escaped.append("\\'");
            } else if (c == '"' && inNBT) {
                // Handle double quotes in NBT data
                if (!inQuote) {
                    escaped.append("\\\"");
                    inQuote = true;
                } else {
                    escaped.append("\\\"");
                    inQuote = false;
                }
            } else {
                escaped.append(c);
            }
        }

        return escaped.toString();
    }
}
