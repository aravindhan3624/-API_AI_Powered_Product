package com.api.automation.dashboard;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** One project execution (public fields = JSON). */
public class RunView {
    public String runId;
    public String project;
    public String displayName;
    public String env;
    public String baseUrl;
    public String status = "RUNNING";  // RUNNING / COMPLETED
    public long startedAt;
    public long finishedAt;
    public String currentTestId;
    public String excelReport;
    public String htmlReport;
    public String htmlReportUrl;    // /report/CKYC/Reports_... (served by the live console)
    public String excelReportUrl;
    public String hubUrl;           // /report/index.html - every run, project-wise
    public String vaptPdf;          // VAPT_Report_....pdf (or .html when no browser was found for the PDF)
    public String failPdf;          // Failure_Report_....pdf
    public String vaptPdfUrl;
    public String failPdfUrl;
    public String pdfNote;

    // ── screen recording (only when the runner enables it) ──
    public boolean recEnabled;
    public String recState = "OFF";   // OFF / WAITING (for the tester to press Start) / RECORDING / STOPPED / SKIPPED / ERROR
    public String recName;            // Recording_CKYC_16-Oct-26_02-07-42
    public String recDir;             // folder the video is written to
    public String recFile;            // recordings/Recording_....webm   (relative to the project report folder)
    public String recShot;            // recordings/Recording_..._final.png
    public String recMessage;
    public long recStartedAt;
    public long recStoppedAt;
    public String aiStatus;
    public String testedBy;
    public boolean paused;
    public long pausedAt;
    public long pausedTotalMs;
    public final List<TestView> tests = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

    public TestView find(String id) {
        for (TestView t : tests) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
