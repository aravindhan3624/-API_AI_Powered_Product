package com.api.product.core.load;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Self-contained HTML report of one load test (no external files, charts drawn as inline SVG). */
public final class LoadReport {

    private LoadReport() {}

    public static String html(String project, String baseUrl, LoadRunner.Config cfg, LoadRunner.Result r) {
        String when = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(r.startedAt));
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Load test - ")
                .append(esc(project)).append("</title><style>body{font:14px/1.5 'Segoe UI',Arial,sans-serif;margin:0;background:#f3f5fa;color:#17203a}.w{max-width:1100px;margin:0 auto;padding:24px}")
                .append("h1{margin:0 0 4px}.card{background:#fff;border:1px solid #e3e7f1;border-radius:12px;padding:16px;margin:14px 0}table{width:100%;border-collapse:collapse}th,td{padding:7px 9px;border-top:1px solid #e3e7f1;text-align:right;font-size:13px}")
                .append("th:first-child,td:first-child{text-align:left}th{font-size:11px;color:#66708a;text-transform:uppercase}.k{display:inline-block;margin:0 26px 8px 0}.k b{display:block;font-size:22px}.k span{font-size:11px;color:#66708a;text-transform:uppercase}")
                .append(".ok{color:#0e8a4a}.bad{color:#d3322c}.mono{font-family:Consolas,monospace}.v{padding:10px 14px;border-radius:10px;margin:6px 0}.v.pass{background:#e7f6ee;border:1px solid #9ad3b4}.v.fail{background:#fdeceb;border:1px solid #efb1ae}</style></head><body><div class=\"w\">");
        h.append("<h1>Load test report</h1><div style=\"color:#66708a\">").append(esc(project)).append(" &middot; ").append(esc(baseUrl)).append(" &middot; ").append(when).append("</div>");
        h.append("<div class=\"card\"><div class=\"v ").append(r.passed ? "pass" : "fail").append("\"><b>").append(r.passed ? "PASSED" : "FAILED").append("</b> &middot; state ").append(esc(r.state)).append("</div>");
        for (String v : r.verdict) h.append("<div>").append(esc(v)).append("</div>");
        double secs = Math.max(1, ((r.finishedAt == 0 ? System.currentTimeMillis() : r.finishedAt) - r.startedAt) / 1000.0);
        h.append("</div><div class=\"card\"><div class=\"k\"><b>").append(cfg.users).append("</b><span>virtual users</span></div><div class=\"k\"><b>").append(cfg.durationSec).append(" s</b><span>planned</span></div>")
                .append("<div class=\"k\"><b>").append(r.totalRequests).append("</b><span>requests</span></div><div class=\"k\"><b>").append(String.format(Locale.ROOT, "%.1f", r.totalRequests / secs)).append("/s</b><span>throughput</span></div>")
                .append("<div class=\"k\"><b class=\"").append(r.totalErrors == 0 ? "ok" : "bad").append("\">").append(r.totalErrors).append("</b><span>errors</span></div>")
                .append("<div class=\"k\"><b>").append(cfg.p95Ms > 0 ? "&le; " + cfg.p95Ms + " ms" : "not set").append("</b><span>p95 limit</span></div><div class=\"k\"><b>").append(cfg.maxErrorPct >= 0 ? "&le; " + cfg.maxErrorPct + "%" : "not set").append("</b><span>error limit</span></div></div>");
        h.append("<div class=\"card\"><b>Requests per second and average latency</b>").append(chart(r.series)).append("</div>");
        h.append("<div class=\"card\"><b>Per API</b><table><thead><tr><th>API</th><th>Requests</th><th>Errors</th><th>Err %</th><th>Min</th><th>Avg</th><th>p50</th><th>p90</th><th>p95</th><th>p99</th><th>Max</th><th>req/s</th></tr></thead><tbody>");
        for (LoadRunner.ApiStats a : r.apis) {
            h.append("<tr><td class=\"mono\">").append(esc(a.api)).append(a.lastError.isEmpty() ? "" : "<div style=\"color:#66708a;font-size:12px\">" + esc(a.lastError) + "</div>").append("</td><td>").append(a.count).append("</td><td class=\"")
                    .append(a.errors == 0 ? "" : "bad").append("\">").append(a.errors).append("</td><td>").append(a.errorPct).append("</td><td>").append(a.minMs).append("</td><td>").append(a.avgMs).append("</td><td>").append(a.p50Ms)
                    .append("</td><td>").append(a.p90Ms).append("</td><td class=\"").append(a.thresholdFailed ? "bad" : "").append("\"><b>").append(a.p95Ms).append("</b></td><td>").append(a.p99Ms).append("</td><td>").append(a.maxMs).append("</td><td>").append(a.rps).append("</td></tr>");
        }
        h.append("</tbody></table><div style=\"color:#66708a;font-size:12px;margin-top:6px\">Latencies in milliseconds. Each virtual user has its own session and repeats the chosen valid requests. Limits: at most ")
                .append(LoadRunner.MAX_USERS).append(" users and ").append(LoadRunner.MAX_SECONDS).append(" seconds; the test stops by itself when the target starts failing.</div></div></div></body></html>");
        return h.toString();
    }

    private static String chart(List<LoadRunner.Point> pts) {
        if (pts.size() < 2) return "<div style=\"color:#66708a\">Too short for a chart.</div>";
        int w = 1000, ht = 180, pad = 30;
        int maxReq = 1;
        long maxLat = 1;
        for (LoadRunner.Point p : pts) {
            maxReq = Math.max(maxReq, p.requests);
            maxLat = Math.max(maxLat, p.avgMs);
        }
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            double x = pad + (w - 2 * pad) * (double) i / (pts.size() - 1);
            a.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, ht - pad - (ht - 2 * pad) * (double) pts.get(i).requests / maxReq));
            b.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, ht - pad - (ht - 2 * pad) * (double) pts.get(i).avgMs / maxLat));
        }
        return "<svg viewBox=\"0 0 " + w + " " + ht + "\" width=\"100%\" role=\"img\" aria-label=\"throughput and latency over time\"><rect x=\"" + pad + "\" y=\"" + pad + "\" width=\"" + (w - 2 * pad) + "\" height=\"" + (ht - 2 * pad)
                + "\" fill=\"none\" stroke=\"#e3e7f1\"/><polyline fill=\"none\" stroke=\"#2f5bd8\" stroke-width=\"2\" points=\"" + a + "\"/><polyline fill=\"none\" stroke=\"#c47a0a\" stroke-width=\"2\" points=\"" + b + "\"/>"
                + "<text x=\"" + pad + "\" y=\"16\" font-size=\"12\" fill=\"#2f5bd8\">requests/s (max " + maxReq + ")</text><text x=\"260\" y=\"16\" font-size=\"12\" fill=\"#c47a0a\">average latency ms (max " + maxLat + ")</text>"
                + "<text x=\"" + (w - pad) + "\" y=\"" + (ht - 8) + "\" font-size=\"11\" text-anchor=\"end\" fill=\"#66708a\">" + pts.size() + " s</text></svg>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
