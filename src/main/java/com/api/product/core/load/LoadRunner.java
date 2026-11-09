package com.api.product.core.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.api.product.core.engine.ModelRunner;
import com.api.product.core.engine.RulePack;
import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;

/**
 * Basic, safe load and performance testing. Every virtual user is its own session (own login, cookies and variables) and repeats the
 * chosen valid (positive) requests for the given time. Per API it measures throughput, errors and latency percentiles, evaluates the
 * thresholds, and stops by itself when the target starts failing (circuit breaker). Hard caps keep it from becoming an attack tool.
 */
public final class LoadRunner {

    public static final int MAX_USERS = 100;
    public static final int MAX_SECONDS = 600;

    public static final class Config {
        public List<String> testIds = new ArrayList<>();
        public int users = 5;
        public int durationSec = 30;
        public int rampSec = 5;
        public int thinkMs = 200;
        /** Threshold: 95th percentile latency of every API (0 = not checked). */
        public long p95Ms = 0;
        /** Threshold: share of failed requests in percent (negative = not checked). */
        public double maxErrorPct = 5.0;

        public void validate() {
            if (testIds.isEmpty()) throw new IllegalArgumentException("Choose at least one request to load test");
            if (users < 1 || users > MAX_USERS) throw new IllegalArgumentException("Virtual users must be between 1 and " + MAX_USERS);
            if (durationSec < 5 || durationSec > MAX_SECONDS) throw new IllegalArgumentException("Duration must be between 5 and " + MAX_SECONDS + " seconds");
            if (rampSec < 0 || rampSec > durationSec) throw new IllegalArgumentException("Ramp-up cannot be longer than the test");
            if (thinkMs < 0 || thinkMs > 60000) throw new IllegalArgumentException("Think time must be between 0 and 60000 ms");
        }
    }

    public static final class ApiStats {
        public String testId;
        public String api;
        public long count;
        public long errors;
        public long minMs;
        public long avgMs;
        public long p50Ms;
        public long p90Ms;
        public long p95Ms;
        public long p99Ms;
        public long maxMs;
        public double rps;
        public double errorPct;
        public String lastError = "";
        public boolean thresholdFailed;
    }

    public static final class Point {
        public int second;
        public int requests;
        public int errors;
        public long avgMs;
        public int activeUsers;
    }

    public static final class Result {
        public volatile String state = "RUNNING";   // RUNNING | DONE | CANCELLED | ABORTED_SAFETY | ERROR
        public volatile String note = "";
        public volatile long startedAt = System.currentTimeMillis();
        public volatile long finishedAt;
        public volatile int activeUsers;
        public volatile long totalRequests;
        public volatile long totalErrors;
        public final List<ApiStats> apis = new CopyOnWriteArrayList<>();
        public final List<Point> series = new CopyOnWriteArrayList<>();
        public volatile boolean passed;
        public final List<String> verdict = new CopyOnWriteArrayList<>();
    }

    private static final class Sample {
        final long ms;
        final boolean error;

        Sample(long ms, boolean error) {
            this.ms = ms;
            this.error = error;
        }
    }

    private LoadRunner() {}

    public static Result run(ApiModel model, RulePack rules, Config cfg, AtomicBoolean cancel, Result res) {
        cfg.validate();
        Map<String, List<Sample>> samples = new ConcurrentHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> lastErr = new ConcurrentHashMap<>();
        for (String id : cfg.testIds) {
            samples.put(id, Collections.synchronizedList(new ArrayList<>()));
            names.put(id, id);
        }
        AtomicLong reqs = new AtomicLong(), errs = new AtomicLong(), lat = new AtomicLong();
        AtomicInteger active = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        long start = System.currentTimeMillis();
        long end = start + cfg.durationSec * 1000L;
        List<Thread> threads = new ArrayList<>();
        for (int u = 0; u < cfg.users; u++) {
            final long delay = cfg.rampSec == 0 ? 0 : (long) u * cfg.rampSec * 1000L / cfg.users;
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    active.incrementAndGet();
                    ModelRunner runner = new ModelRunner(model, rules, null, true);
                    java.util.Set<String> ids = new java.util.LinkedHashSet<>(cfg.testIds);
                    while (!stop.get() && !cancel.get() && System.currentTimeMillis() < end) {
                        for (TestResult r : runner.run(ids, null, () -> stop.get() || cancel.get() || System.currentTimeMillis() >= end)) {
                            if (r.httpStatus == null && !"BLOCKED".equals(r.outcome)) continue;
                            boolean err = !"PASS".equals(r.outcome);
                            if (r.testCaseId != null && samples.containsKey(r.testCaseId)) {
                                names.put(r.testCaseId, (r.method == null ? "" : r.method + " ") + (r.url == null ? r.testCaseId : r.url.replaceFirst("^https?://[^/]+", "")));
                                samples.get(r.testCaseId).add(new Sample(r.latencyMs, err));
                                if (err && r.remark != null) lastErr.put(r.testCaseId, r.remark);
                            }
                            reqs.incrementAndGet();
                            lat.addAndGet(r.latencyMs);
                            if (err) errs.incrementAndGet();
                        }
                        if (cfg.thinkMs > 0) Thread.sleep(cfg.thinkMs);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    lastErr.put("_", e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    active.decrementAndGet();
                }
            }, "load-vu-" + u);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        // sampler: one point per second + circuit breaker
        long prevReq = 0, prevErr = 0, prevLat = 0;
        long[] winReq = new long[10], winErr = new long[10];
        int sec = 0;
        while (System.currentTimeMillis() < end && !cancel.get() && !stop.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
            sec++;
            long r = reqs.get(), e = errs.get(), l = lat.get();
            Point p = new Point();
            p.second = sec;
            p.requests = (int) (r - prevReq);
            p.errors = (int) (e - prevErr);
            p.avgMs = p.requests == 0 ? 0 : (l - prevLat) / p.requests;
            p.activeUsers = active.get();
            res.series.add(p);
            winReq[sec % 10] = p.requests;
            winErr[sec % 10] = p.errors;
            prevReq = r;
            prevErr = e;
            prevLat = l;
            res.activeUsers = p.activeUsers;
            res.totalRequests = r;
            res.totalErrors = e;
            long wr = 0, we = 0;
            for (int i = 0; i < 10; i++) {
                wr += winReq[i];
                we += winErr[i];
            }
            if (sec >= 10 && wr >= 20 && we * 100.0 / wr > 50.0) {
                stop.set(true);
                res.state = "ABORTED_SAFETY";
                res.note = "Stopped automatically: more than half of the last " + wr + " requests failed. The target may be overloaded or the test is misconfigured.";
            }
            snapshot(res, samples, names, lastErr, cfg, sec);
        }
        stop.set(true);
        for (Thread t : threads) {
            try {
                t.join(15000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        int dur = Math.max(1, (int) ((System.currentTimeMillis() - start) / 1000));
        res.totalRequests = reqs.get();
        res.totalErrors = errs.get();
        res.activeUsers = 0;
        snapshot(res, samples, names, lastErr, cfg, dur);
        if (!"ABORTED_SAFETY".equals(res.state)) res.state = cancel.get() ? "CANCELLED" : "DONE";
        evaluate(res, cfg);
        res.finishedAt = System.currentTimeMillis();
        return res;
    }

    private static void snapshot(Result res, Map<String, List<Sample>> samples, Map<String, String> names, Map<String, String> lastErr, Config cfg, int seconds) {
        List<ApiStats> out = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> e : samples.entrySet()) {
            long[] l;
            long errors = 0;
            synchronized (e.getValue()) {
                l = new long[e.getValue().size()];
                int i = 0;
                for (Sample s : e.getValue()) {
                    l[i++] = s.ms;
                    if (s.error) errors++;
                }
            }
            ApiStats a = new ApiStats();
            a.testId = e.getKey();
            a.api = names.getOrDefault(e.getKey(), e.getKey());
            a.count = l.length;
            a.errors = errors;
            if (l.length > 0) {
                java.util.Arrays.sort(l);
                long sum = 0;
                for (long v : l) sum += v;
                a.minMs = l[0];
                a.maxMs = l[l.length - 1];
                a.avgMs = sum / l.length;
                a.p50Ms = pct(l, 50);
                a.p90Ms = pct(l, 90);
                a.p95Ms = pct(l, 95);
                a.p99Ms = pct(l, 99);
            }
            a.rps = Math.round(10.0 * l.length / Math.max(1, seconds)) / 10.0;
            a.errorPct = l.length == 0 ? 0 : Math.round(1000.0 * errors / l.length) / 10.0;
            a.lastError = lastErr.getOrDefault(e.getKey(), "");
            out.add(a);
        }
        res.apis.clear();
        res.apis.addAll(out);
    }

    private static long pct(long[] sorted, int p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static void evaluate(Result res, Config cfg) {
        res.verdict.clear();
        boolean ok = true;
        if ("ABORTED_SAFETY".equals(res.state)) {
            ok = false;
            res.verdict.add("FAILED: " + res.note);
        }
        for (ApiStats a : res.apis) {
            if (a.count == 0) {
                ok = false;
                a.thresholdFailed = true;
                res.verdict.add("FAILED: " + a.api + " never completed a request" + (a.lastError.isEmpty() ? "" : " (" + a.lastError + ")"));
                continue;
            }
            if (cfg.p95Ms > 0 && a.p95Ms > cfg.p95Ms) {
                ok = false;
                a.thresholdFailed = true;
                res.verdict.add("FAILED: " + a.api + " p95 " + a.p95Ms + " ms is above the limit " + cfg.p95Ms + " ms");
            }
            if (cfg.maxErrorPct >= 0 && a.errorPct > cfg.maxErrorPct) {
                ok = false;
                a.thresholdFailed = true;
                res.verdict.add("FAILED: " + a.api + " error rate " + a.errorPct + "% is above the limit " + cfg.maxErrorPct + "%"
                        + (a.lastError.isEmpty() ? "" : " (" + a.lastError + ")"));
            }
        }
        if (ok) res.verdict.add("All thresholds met.");
        res.passed = ok;
    }
}
