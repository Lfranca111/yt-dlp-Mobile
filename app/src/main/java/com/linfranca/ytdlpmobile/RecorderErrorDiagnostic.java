package com.linfranca.ytdlpmobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded recorder failure details for saved logs; never include command lines or credentials. */
final class RecorderErrorDiagnostic {
    private static final Pattern URL = Pattern.compile("(?i)(?:https?|file|content|blob)://[^\\s'\"<>]+|blob:[^\\s'\"<>]+");
    private static final Pattern AUTH = Pattern.compile("(?i)(?:cookie|set-cookie|authorization|password|passwd|api[_-]?key|key|secret|credentials?|client[_-]?secret|refresh[_-]?token|access[_-]?token|auth[_-]?token|session(?:id)?|token|signature|sig)\\s*['\"]?\\s*[:=].*");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_VALUE = Pattern.compile("[A-Za-z0-9_-]{32,}");
    private static final Pattern PATH = Pattern.compile("(?:[A-Za-z]:\\\\|/(?:data|storage|sdcard|tmp|home|workspace)/)[^\\s'\"<>]+");
    static String safeLine(String input) { return safeLine(input, 400); }
    static String safeLine(String input, int limit) {
        if (input == null) return null;
        String line = input.trim();
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (line.isEmpty() || lower.contains("command-line") || lower.contains("command line") ||
                lower.contains("--add-header") || lower.contains("--cookies") ||
                lower.startsWith("[debug]") || lower.startsWith("traceback") ||
                lower.startsWith("file \"") || lower.startsWith("at ")) return null;
        line = URL.matcher(line).replaceAll("[URL removed]");
        line = AUTH.matcher(line).replaceAll("[credential removed]");
        line = EMAIL.matcher(line).replaceAll("[account removed]");
        line = PATH.matcher(line).replaceAll("[local file removed]");
        line = LONG_VALUE.matcher(line).replaceAll("[long value removed]");
        line = line.replaceAll("[\\p{Cntrl}]", " ").trim();
        return line.isEmpty() ? null : line.substring(0, Math.min(limit, line.length()));
    }
    static List<String> describe(Throwable error) {
        List<String> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; error != null && depth < 3 && seen.add(error); depth++, error = error.getCause()) {
            String message = error.getMessage();
            result.add("Failure detail type=" + error.getClass().getSimpleName());
            if (message == null || message.isBlank()) continue;
            // Usually the final ERROR line explains failure; omit unbounded extractor output.
            String tail = message.substring(Math.max(0, message.length() - 16384));
            String[] lines = tail.split("\\r?\\n");
            List<String> clean = new ArrayList<>();
            for (String line : lines) {
                String safe = safeLine(line);
                if (safe != null && !clean.contains(safe)) clean.add(safe);
            }
            for (int i = Math.max(0, clean.size() - 8); i < clean.size(); i++)
                result.add("Failure detail: " + clean.get(i));
        }
        return result;
    }
}
