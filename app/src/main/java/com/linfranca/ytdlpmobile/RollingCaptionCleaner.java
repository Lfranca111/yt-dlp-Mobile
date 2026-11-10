package com.linfranca.ytdlpmobile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes carried-over words from YouTube-style rolling VTT captions. */
final class RollingCaptionCleaner {
    private static final Pattern TIMING = Pattern.compile("^((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})\\s+-->\\s+((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})(.*)$");
    private static final Pattern WORD_TIME = Pattern.compile("<(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final long MAX_CUE_GAP_MS = 250;

    private RollingCaptionCleaner() { }

    /** Returns the number of redundant cues removed; leaves unrelated VTT untouched. */
    static int clean(File file) throws IOException {
        String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Result result = cleanText(original);
        if (result.removed == 0 && !result.changed) return 0;
        File temporary = File.createTempFile("captions-", ".vtt", file.getParentFile());
        try {
            Files.write(temporary.toPath(), result.text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
        return result.removed;
    }

    static Result cleanText(String input) {
        String normalized = input.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("WEBVTT")) return new Result(input, 0, false);
        // A whitespace-only payload line is used by YouTube's 10 ms handoff cues.
        // Only an actually empty line separates VTT cue blocks here.
        String[] blocks = normalized.split("\\n\\n+");
        if (blocks.length < 4) return new Result(input, 0, false);
        List<Cue> cues = new ArrayList<>();
        for (int i = 1; i < blocks.length; i++) {
            String[] lines = blocks[i].split("\n");
            int timingIndex = -1;
            Matcher timing = null;
            for (int j = 0; j < Math.min(lines.length, 2); j++) {
                timing = TIMING.matcher(lines[j].trim());
                if (timing.matches()) { timingIndex = j; break; }
            }
            // Leave files with notes, styles, regions, or unfamiliar cue blocks untouched.
            if (timingIndex < 0 || lines.length <= timingIndex + 1) return new Result(input, 0, false);
            String payload = String.join("\n", Arrays.copyOfRange(lines, timingIndex + 1, lines.length));
            String plain = TAG.matcher(WORD_TIME.matcher(payload).replaceAll("")).replaceAll("")
                    .replaceAll("(?m)^\\s+$", "").trim().replaceAll("\\s+", " ");
            int carriedWords = 0;
            boolean timedLine = false;
            for (String line : payload.split("\n")) {
                if (WORD_TIME.matcher(line).find()) { timedLine = true; break; }
                String previousLine = TAG.matcher(line).replaceAll("").trim();
                if (!previousLine.isEmpty()) carriedWords += words(previousLine).size();
            }
            cues.add(new Cue(lines[timingIndex].trim(), millis(timing.group(1)), millis(timing.group(2)),
                    plain, WORD_TIME.matcher(payload).find(), timedLine ? carriedWords : -1));
        }
        int rollingEvidence = 0;
        for (int i = 1; i < cues.size(); i++) {
            Cue previous = cues.get(i - 1), current = cues.get(i);
            if (near(previous, current) && (previous.wordTimes || current.wordTimes)
                    && overlap(words(previous.plain), words(current.plain)) >= 2) rollingEvidence++;
        }
        if (rollingEvidence < 2) return new Result(input, 0, false);

        StringBuilder out = new StringBuilder(blocks[0]).append("\n\n");
        int removed = 0;
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            List<String> current = words(cue.plain);
            int repeated = i > 0 && near(cues.get(i - 1), cue)
                    ? overlap(words(cues.get(i - 1).plain), current) : 0;
            // Fresh word-timed speech must never disappear merely because it repeats earlier words.
            if (cue.carriedWords >= 0) repeated = Math.min(repeated, cue.carriedWords);
            if (repeated == current.size() && cue.end - cue.start > 50) repeated = 0;
            if (repeated == current.size()) { removed++; continue; }
            // A carried-over single word is safe to remove once the file is identified as rolling.
            if (repeated == 1 && words(cues.get(i - 1).plain).size() > 1) repeated = 0;
            String fresh = String.join(" ", current.subList(repeated, current.size()));
            if (fresh.isEmpty()) { removed++; continue; }
            out.append(cue.timing).append('\n').append(fresh).append("\n\n");
        }
        return new Result(out.toString(), removed, true);
    }

    private static boolean near(Cue previous, Cue current) {
        long gap = current.start - previous.end;
        return gap >= -MAX_CUE_GAP_MS && gap <= MAX_CUE_GAP_MS;
    }

    private static List<String> words(String text) {
        return text.isEmpty() ? new ArrayList<>() : Arrays.asList(text.split("\\s+"));
    }

    private static int overlap(List<String> previous, List<String> current) {
        for (int n = Math.min(previous.size(), current.size()); n > 0; n--) {
            boolean matches = true;
            for (int j = 0; j < n; j++) {
                if (!previous.get(previous.size() - n + j).equals(current.get(j))) { matches = false; break; }
            }
            if (matches) return n;
        }
        return 0;
    }

    private static long millis(String time) {
        String[] pieces = time.split("[:.]");
        int off = pieces.length == 4 ? 0 : -1;
        return (off == 0 ? Long.parseLong(pieces[0]) * 3_600_000 : 0)
                + Long.parseLong(pieces[1 + off]) * 60_000
                + Long.parseLong(pieces[2 + off]) * 1000
                + Long.parseLong(pieces[3 + off]);
    }

    static final class Result {
        final String text;
        final int removed;
        final boolean changed;
        Result(String text, int removed, boolean changed) {
            this.text = text; this.removed = removed; this.changed = changed;
        }
    }

    private static final class Cue {
        final String timing;
        final long start, end;
        final String plain;
        final boolean wordTimes;
        final int carriedWords;
        Cue(String timing, long start, long end, String plain, boolean wordTimes, int carriedWords) {
            this.timing = timing; this.start = start; this.end = end;
            this.plain = plain; this.wordTimes = wordTimes; this.carriedWords = carriedWords;
        }
    }
}
