package com.api.product.studio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.api.product.core.engine.TargetProbe;
import com.api.product.core.model.ApiModel;
import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Runs the approved tests of a project on a schedule while Studio is open (daily, weekly or every N minutes). Each run goes through
 * the same pipeline as a manual run (reports included) and is recorded in the project history. A scheduled run is skipped, with the
 * reason noted, when credentials are missing or the target is not the API. For unattended runs without Studio use the CLI and the
 * Windows Task Scheduler / CI templates.
 */
public final class Scheduler {

    public static final class Sched {
        public String id;
        public String label;
        /** daily | weekly | interval */
        public String kind = "daily";
        public String time = "02:00";
        public String day = "MON";
        public int everyMinutes = 60;
        public boolean enabled = true;
        public long nextRun;
        public Long lastRun;
        public String lastResult;
        public String lastNote;
    }

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ProjectStore store;
    private final RunService runs;
    private final Map<String, RunService.Run> active = new ConcurrentHashMap<>();
    private ScheduledExecutorService ex;

    public Scheduler(ProjectStore store, RunService runs) {
        this.store = store;
        this.runs = runs;
    }

    public void start() {
        ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "studio-scheduler");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::tick, 20, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        if (ex != null) ex.shutdownNow();
    }

    // ------------------------------------------------------------------ storage

    private Path file(String project) {
        return store.dir(project).resolve("schedules.json");
    }

    public synchronized List<Sched> list(String project) {
        try {
            if (!Files.exists(file(project))) return new ArrayList<>();
            return M.readValue(file(project).toFile(), new TypeReference<List<Sched>>() { });
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private synchronized void save(String project, List<Sched> l) throws IOException {
        M.writeValue(file(project).toFile(), l);
    }

    public synchronized Sched add(String project, Sched s) throws IOException {
        if (!List.of("daily", "weekly", "interval").contains(s.kind)) throw new IllegalArgumentException("kind must be daily, weekly or interval");
        if (!"interval".equals(s.kind) && !s.time.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Time must be HH:mm (24 hour)");
        if ("weekly".equals(s.kind) && !s.day.matches("MON|TUE|WED|THU|FRI|SAT|SUN")) throw new IllegalArgumentException("Choose a day of the week");
        if ("interval".equals(s.kind) && (s.everyMinutes < 5 || s.everyMinutes > 10080)) throw new IllegalArgumentException("Interval must be between 5 minutes and 7 days");
        List<Sched> l = list(project);
        s.id = "S" + (l.stream().mapToInt(x -> Integer.parseInt(x.id.substring(1))).max().orElse(0) + 1);
        if (s.label == null || s.label.isBlank()) s.label = describe(s);
        s.nextRun = next(s, System.currentTimeMillis());
        l.add(s);
        save(project, l);
        return s;
    }

    public synchronized void remove(String project, String id) throws IOException {
        List<Sched> l = list(project);
        l.removeIf(x -> x.id.equals(id));
        save(project, l);
    }

    public synchronized void toggle(String project, String id, boolean enabled) throws IOException {
        List<Sched> l = list(project);
        for (Sched s : l) {
            if (s.id.equals(id)) {
                s.enabled = enabled;
                if (enabled) s.nextRun = next(s, System.currentTimeMillis());
            }
        }
        save(project, l);
    }

    public static String describe(Sched s) {
        return switch (s.kind) {
            case "weekly" -> "Every " + s.day + " at " + s.time;
            case "interval" -> "Every " + (s.everyMinutes % 60 == 0 ? s.everyMinutes / 60 + " hour(s)" : s.everyMinutes + " minutes");
            default -> "Every day at " + s.time;
        };
    }

    static long next(Sched s, long from) {
        ZoneId z = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(from), z);
        if ("interval".equals(s.kind)) return from + s.everyMinutes * 60_000L;
        LocalTime t = LocalTime.parse(s.time);
        LocalDateTime c = now.with(t);
        if ("weekly".equals(s.kind)) {
            DayOfWeek d = DayOfWeek.valueOf(switch (s.day) {
                case "MON" -> "MONDAY"; case "TUE" -> "TUESDAY"; case "WED" -> "WEDNESDAY"; case "THU" -> "THURSDAY";
                case "FRI" -> "FRIDAY"; case "SAT" -> "SATURDAY"; default -> "SUNDAY"; });
            c = now.with(TemporalAdjusters.nextOrSame(d)).with(t);
            if (!c.isAfter(now)) c = c.plusWeeks(1);
        } else if (!c.isAfter(now)) {
            c = c.plusDays(1);
        }
        return c.atZone(z).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------ execution

    /** Public so a test or the UI can trigger a due check; normally called every 30 seconds. */
    public void tick() {
        try {
            long now = System.currentTimeMillis();
            for (String project : store.projects()) {
                List<Sched> l = list(project);
                if (l.isEmpty()) continue;
                boolean changed = false;
                for (Sched s : l) {
                    String key = project + "/" + s.id;
                    RunService.Run r = active.get(key);
                    if (r != null && !"RUNNING".equals(r.state)) {
                        s.lastResult = summarize(r);
                        s.lastNote = "finished";
                        active.remove(key);
                        audit(project, s, "scheduled run finished: " + s.lastResult);
                        changed = true;
                    }
                    if (!s.enabled || s.nextRun > now || active.containsKey(key)) continue;
                    s.lastRun = now;
                    s.nextRun = next(s, now);
                    changed = true;
                    fire(project, s, key);
                }
                if (changed) save(project, l);
            }
        } catch (Exception ignored) {
            // the scheduler must never die; the next tick tries again
        }
    }

    private void fire(String project, Sched s, String key) {
        try {
            com.api.product.core.license.LicenseManager.Status lic = com.api.product.core.license.LicenseManager.status();
            if (!lic.active()) {
                s.lastResult = "SKIPPED";
                s.lastNote = lic.message;
                return;
            }
            ApiModel m = store.load(project);
            List<String> missing = CredentialSetup.missingRefs(m);
            if (!missing.isEmpty()) {
                s.lastResult = "SKIPPED";
                s.lastNote = "credentials missing: " + String.join(", ", missing.stream().limit(4).toArray(String[]::new));
                audit(project, s, "scheduled run skipped - " + s.lastNote);
                return;
            }
            String base = m.environments.isEmpty() ? "" : m.environments.get(0).baseUrl;
            if (base == null || base.isBlank() || base.contains("${")) {
                s.lastResult = "SKIPPED";
                s.lastNote = "no usable application URL";
                return;
            }
            TargetProbe.Result pr = new TargetProbe().probe(m, base, 3);
            if (!pr.ok && !pr.unknown) {
                s.lastResult = "SKIPPED";
                s.lastNote = pr.message;
                audit(project, s, "scheduled run skipped - " + pr.message);
                return;
            }
            if (runs.activeFor(project) != null) {
                s.lastResult = "SKIPPED";
                s.lastNote = "another run is in progress";
                return;
            }
            RunService.Run r = runs.start(project, null, false);
            active.put(key, r);
            s.lastResult = "RUNNING";
            s.lastNote = "run " + r.id + " (" + r.total + " tests)";
        } catch (Exception e) {
            s.lastResult = "ERROR";
            s.lastNote = e.getMessage();
        }
    }

    private static String summarize(RunService.Run r) {
        Map<String, Integer> c = new TreeMap<>(r.counts);
        int pass = c.getOrDefault("PASS", 0), fail = c.getOrDefault("FAIL", 0);
        return ("ERROR".equals(r.state) ? "ERROR " + r.error : (pass + fail == 0 ? "no decided tests" : Math.round(1000.0 * pass / (pass + fail)) / 10.0 + "% pass")) + " " + c;
    }

    private void audit(String project, Sched s, String note) {
        ObjectNode ev = M.createObjectNode();
        ev.put("note", "[schedule " + s.label + "] " + note);
        store.audit(project, "scheduler", ev);
    }
}
