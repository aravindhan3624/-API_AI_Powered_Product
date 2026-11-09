package com.api.product.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.api.product.core.store.ProjectStore;

/**
 * Everything the assistant "knows": curated product knowledge shipped with the product (global scope) plus the
 * documents a project owner drops into projects/NAME/docs (BRD, flow descriptions, API documents: .md .txt .docx .pdf).
 * Retrieval is local BM25; answers always carry their source.
 */
public final class KnowledgeBase {

    private static final String[] GLOBAL = { "owasp-api-top10.md", "failure-playbook.md", "test-design-techniques.md",
            "banking-kyc-notes.md", "product-help.md" };
    private static final Pattern H2 = Pattern.compile("(?m)^## (.+)$");

    private final Bm25Index index = new Bm25Index();
    private final ProjectStore store;

    public KnowledgeBase(ProjectStore store) {
        this.store = store;
        for (String f : GLOBAL) loadResource(f);
    }

    private void loadResource(String file) {
        try (InputStream in = KnowledgeBase.class.getClassLoader().getResourceAsStream("ai/knowledge/" + file)) {
            if (in == null) return;
            split(new String(in.readAllBytes(), StandardCharsets.UTF_8), file, "global");
        } catch (IOException ignored) {
            // a missing knowledge file only lowers coverage
        }
    }

    /** Splits markdown into sections at "## " headings. */
    private void split(String text, String source, String scope) {
        Matcher m = H2.matcher(text);
        List<int[]> marks = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[] { m.start(), m.end() });
            titles.add(m.group(1).trim());
        }
        if (marks.isEmpty()) {
            chunk(text, source, source, scope);
            return;
        }
        for (int i = 0; i < marks.size(); i++) {
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            chunk(text.substring(marks.get(i)[1], end).trim(), titles.get(i), source, scope);
        }
    }

    /** Long plain text (BRD) is cut into paragraphs of about 700 characters. */
    private void chunk(String body, String title, String source, String scope) {
        if (body.length() <= 1200) {
            index.add(new Bm25Index.Doc(source + "#" + title, title, body, source, scope));
            return;
        }
        StringBuilder cur = new StringBuilder();
        int n = 1;
        for (String para : body.split("\\R\\s*\\R|\\R(?=\\s*(?:\\d+[.)]|[-*])\\s)")) {
            if (cur.length() + para.length() > 900 && cur.length() > 0) {
                index.add(new Bm25Index.Doc(source + "#" + title + "-" + n, title + " (part " + n + ")", cur.toString(), source, scope));
                cur.setLength(0);
                n++;
            }
            cur.append(para).append("\n");
        }
        if (cur.length() > 0) index.add(new Bm25Index.Doc(source + "#" + title + "-" + n, title + " (part " + n + ")", cur.toString(), source, scope));
    }

    /** (Re)reads projects/NAME/docs. Returns the number of files indexed. */
    public synchronized int reloadProject(String project) {
        index.removeScope(project);
        Path docs = store.dir(project).resolve("docs");
        int files = 0;
        if (!Files.isDirectory(docs)) return 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(docs)) {
            for (Path p : ds) {
                String text = readText(p);
                if (text == null || text.isBlank()) continue;
                split(text, p.getFileName().toString(), project);
                files++;
            }
        } catch (IOException ignored) {
            // nothing more to index
        }
        return files;
    }

    /** Plain text of a document file, or null when the type is not supported. */
    public static String readText(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        try {
            if (n.endsWith(".md") || n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".csv")) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
            if (n.endsWith(".docx")) {
                try (InputStream in = Files.newInputStream(p); XWPFDocument d = new XWPFDocument(in);
                        XWPFWordExtractor x = new XWPFWordExtractor(d)) {
                    return x.getText();
                }
            }
            if (n.endsWith(".pdf")) {
                try (PDDocument d = PDDocument.load(p.toFile())) {
                    return new PDFTextStripper().getText(d);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public List<Bm25Index.Hit> search(String query, String project, int k) {
        return index.search(query, project, k);
    }

    public int size() {
        return index.size();
    }
}
