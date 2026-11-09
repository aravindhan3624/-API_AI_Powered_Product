package com.api.product.studio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.api.product.core.report.PdfBuilder;

/** Renders the bundled user manual (Markdown subset: # ## headings, - lists, paragraphs, **bold**, `code`) as HTML and PDF. */
public final class ManualRenderer {

    private ManualRenderer() {}

    public static String markdown() throws IOException {
        return read("/manual/user-manual.md") + "\n" + read("/manual/whats-new.md");
    }

    private static String read(String res) throws IOException {
        try (InputStream in = ManualRenderer.class.getResourceAsStream(res)) {
            if (in == null) throw new IOException("manual part not found in this build: " + res);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static String html() throws IOException {
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>API Quality Studio - User manual</title><style>"
                + "body{font:15px/1.65 'Segoe UI',Arial,sans-serif;margin:0;background:#f3f5fa;color:#17203a}.w{max-width:820px;margin:0 auto;padding:28px 22px 60px}h1{font-size:26px;margin:34px 0 6px;color:#1d3a8a}h2{font-size:18px;margin:22px 0 4px}"
                + "li{margin:4px 0}code{background:#e9efff;border-radius:4px;padding:1px 5px;font-size:13px}.top{background:#0f1d3a;color:#fff;padding:18px 22px}.top b{font-size:18px}a{color:#2f5bd8}</style></head><body><div class=\"top\"><b>API Quality Studio</b> &nbsp; User manual &nbsp; "
                + "<a style=\"color:#9fb4ff\" href=\"/manual.pdf\">Download as PDF</a> &nbsp; <a style=\"color:#9fb4ff\" href=\"/app\">Back to Studio</a></div><div class=\"w\">");
        boolean list = false;
        for (String line : markdown().split("\\R")) {
            String l = line.trim();
            if (l.startsWith("- ")) {
                if (!list) {
                    h.append("<ul>");
                    list = true;
                }
                h.append("<li>").append(inline(l.substring(2))).append("</li>");
                continue;
            }
            if (list) {
                h.append("</ul>");
                list = false;
            }
            if (l.startsWith("## ")) h.append("<h2>").append(inline(l.substring(3))).append("</h2>");
            else if (l.startsWith("# ")) h.append("<h1>").append(inline(l.substring(2))).append("</h1>");
            else if (!l.isEmpty()) h.append("<p>").append(inline(l)).append("</p>");
        }
        if (list) h.append("</ul>");
        return h.append("</div></body></html>").toString();
    }

    private static String inline(String s) {
        String e = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return e.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>").replaceAll("`([^`]+)`", "<code>$1</code>");
    }

    public static void pdf(Path file) throws IOException {
        try (PdfBuilder pdf = new PdfBuilder("API Quality Studio - User manual")) {
            pdf.title("API Quality Studio", "User manual");
            for (String line : markdown().split("\\R")) {
                String l = line.trim();
                if (l.startsWith("## ")) pdf.h2(plain(l.substring(3)));
                else if (l.startsWith("# ")) pdf.h1(plain(l.substring(2)));
                else if (l.startsWith("- ")) pdf.bullet(plain(l.substring(2)));
                else if (!l.isEmpty()) pdf.p(plain(l));
            }
            pdf.save(file);
        }
    }

    private static String plain(String s) {
        return s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1").replaceAll("`([^`]+)`", "$1");
    }

    /** For tests: the headings of the manual. */
    public static List<String> headings() throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : markdown().split("\\R")) if (l.startsWith("#")) out.add(l.replaceAll("^#+\\s*", ""));
        return out;
    }
}
