package com.kodewerk.diskinventory.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs {@code df -h} for the filesystem holding a given path. */
public final class DiskFree {

    /** Parsed {@code df} output: column headers plus one row per filesystem. */
    public record Table(List<String> headers, List<List<String>> rows) {
        public boolean isEmpty() {
            return headers.isEmpty() || rows.isEmpty();
        }
    }

    private DiskFree() {
    }

    public static Table table(Path path) {
        return parse(df(path));
    }

    /**
     * Parses {@code df} output. The trailing "Mounted on" header is merged into
     * one column; only the last column may contain spaces in row data.
     */
    public static Table parse(String raw) {
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();
        if (lines.size() < 2) {
            return new Table(List.of(), List.of());
        }
        List<String> headers = new ArrayList<>(List.of(lines.getFirst().trim().split("\\s+")));
        if (headers.size() >= 2 && headers.getLast().equals("on")) {
            headers.removeLast();
            headers.set(headers.size() - 1, headers.getLast() + " on");
        }
        int columns = headers.size();
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.trim().split("\\s+", columns);
            if (cells.length == columns) {
                rows.add(List.of(cells));
            }
        }
        return new Table(List.copyOf(headers), List.copyOf(rows));
    }

    public static String df(Path path) {
        try {
            Process process = new ProcessBuilder("df", "-h", path.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "df: timed out";
            }
            return output.strip();
        } catch (IOException e) {
            return "df: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "df: interrupted";
        }
    }
}
