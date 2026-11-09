package com.api.automation.report;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reports hub: reports/index.html lists every run, project-wise, newest first, as clickable cards
 * (View report / Excel). It is regenerated after every run and works opened straight from the folder
 * (file://) as well as through the live console (http://localhost:8095/report/index.html).
 *
 * Each report gets a small sidecar  Reports_<P>_<date>_<time>.json  with its summary numbers, so the card can
 * show pass / fail counts without opening the Excel. Reports without a sidecar are still listed.
 */
public final class ReportHub {

    private static final ObjectMapper M = new ObjectMapper();

    private ReportHub() {}

    /** Writes the summary next to the HTML report (same name, .json). */
    public static void writeSidecar(Path htmlReport, Map<String, Object> info) {
        try {
            String json = M.writerWithDefaultPrettyPrinter().writeValueAsString(info);
            Files.writeString(Path.of(htmlReport.toString().replaceAll("\\.html$", ".json")), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("⚠ Report summary not written: " + e.getMessage());
        }
    }

    /** Adds fields (e.g. recording, screenshot) to an existing summary file. */
    @SuppressWarnings("unchecked")
    public static void updateSidecar(Path htmlReport, Map<String, Object> extra) {
        try {
            Path f = Path.of(htmlReport.toString().replaceAll("\\.html$", ".json"));
            Map<String, Object> info = Files.exists(f) ? M.readValue(f.toFile(), LinkedHashMap.class) : new LinkedHashMap<>();
            info.putAll(extra);
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(info), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("\u26A0 Report summary not updated: " + e.getMessage());
        }
    }

    /** Rebuilds reports/index.html from every reports/<PROJECT>/Reports_*.html found. */
    public static Path write(Path base) {
        try {
            Files.createDirectories(base);
            List<Map<String, Object>> runs = new ArrayList<>();
            try (Stream<Path> projects = Files.list(base)) {
                for (Path dir : (Iterable<Path>) projects.filter(Files::isDirectory)::iterator) {
                    // new layout: reports/<P>/<date_time>/Reports_*.html ; older flat reports directly in reports/<P>/
                    List<Path> places = new ArrayList<>();
                    places.add(dir);
                    try (Stream<Path> sub = Files.list(dir)) {
                        sub.filter(Files::isDirectory)
                           .filter(s -> !s.getFileName().toString().endsWith("_files") && !s.getFileName().toString().equals("recordings"))
                           .forEach(places::add);
                    }
                    for (Path place : places) {
                        try (Stream<Path> files = Files.list(place)) {
                            for (Path html : (Iterable<Path>) files
                                    .filter(f -> f.getFileName().toString().matches("Reports_.*\\.html"))::iterator) {
                                runs.add(entry(base, dir, html));
                            }
                        }
                    }
                }
            }
            runs.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("when")).longValue()).reversed());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("generatedAt", System.currentTimeMillis());
            data.put("runs", runs);
            String json = M.writeValueAsString(data).replace("</", "<\\/");

            String page;
            try (InputStream in = ReportHub.class.getResourceAsStream("/dashboard/report_hub.html")) {
                page = in == null ? "<h1>report_hub.html not found on the classpath</h1>"
                        : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path out = base.resolve("index.html");
            Files.writeString(out, page.replace("/*DATA*/null", json), StandardCharsets.UTF_8);
            return out;
        } catch (Exception e) {
            System.out.println("⚠ Reports hub not written: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Path base, Path projectDir, Path html) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        String name = html.getFileName().toString();
        String stem = name.replaceAll("\\.html$", "");
        Path runDir = html.getParent();
        Path sidecar = runDir.resolve(stem + ".json");
        Path excel = runDir.resolve(stem + ".xlsx");

        Map<String, Object> info = new LinkedHashMap<>();
        if (Files.exists(sidecar)) {
            try {
                info = M.readValue(sidecar.toFile(), LinkedHashMap.class);
            } catch (Exception ignored) {
                // unreadable summary - show the report without numbers
            }
        }
        e.putAll(info);
        e.put("project", projectDir.getFileName().toString());
        e.put("name", name);
        e.put("folder", rel(base, runDir));
        e.put("html", rel(base, html));
        e.put("excel", Files.exists(excel) ? rel(base, excel) : null);
        // recording + final screenshot are stored relative to the project folder -> make them relative to reports/
        for (String k : new String[]{"recording", "screenshot", "vaptReport", "failureReport"}) {
            Object v = e.get(k);
            if (v != null) e.put(k, rel(base, runDir.resolve(v.toString())));
        }
        e.putIfAbsent("when", whenOf(stem, Files.getLastModifiedTime(html).toMillis()));
        e.putIfAbsent("mode", stem.contains("_Positive_") ? "POSITIVE" : stem.contains("_Negative_") ? "NEGATIVE" : "ALL");
        e.put("htmlMb", Math.round(Files.size(html) / 1048576.0 * 10) / 10.0);
        return e;
    }

    /** Run time from the report name (..._16-Oct-26_02-07-42 or ..._02-07); file time only when the name has none. */
    private static long whenOf(String stem, long fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("_(\\d{2}-[A-Za-z]{3}-\\d{2})_(\\d{2})-(\\d{2})(?:-(\\d{2}))?$").matcher(stem);
        if (!m.find()) return fallback;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(m.group(1),
                    java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH));
            java.time.LocalDateTime t = d.atTime(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    m.group(4) == null ? 0 : Integer.parseInt(m.group(4)));
            return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String rel(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }
}
