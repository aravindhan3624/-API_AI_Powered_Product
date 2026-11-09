package com.api.product.core.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** Minimal multi-page PDF writer (text, headings, bullets, wrapped tables, page numbers) on top of PDFBox. */
public final class PdfBuilder implements AutoCloseable {

    private static final float MARGIN = 48;
    private static final float BOTTOM = 56;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final PDFont REG = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;

    private final PDDocument doc = new PDDocument();
    private final String footer;
    private PDPage page;
    private PDPageContentStream cs;
    private float y;
    private int pageNo;

    public PdfBuilder(String footer) throws IOException {
        this.footer = footer;
        newPage();
    }

    private void newPage() throws IOException {
        if (cs != null) {
            footer();
            cs.close();
        }
        page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        pageNo++;
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private void footer() throws IOException {
        cs.beginText();
        cs.setFont(REG, 8);
        cs.setNonStrokingColor(0.45f, 0.48f, 0.55f);
        cs.newLineAtOffset(MARGIN, 30);
        cs.showText(clean(footer) + "   |   Page " + pageNo);
        cs.endText();
        cs.setNonStrokingColor(0f, 0f, 0f);
    }

    private void ensure(float needed) throws IOException {
        if (y - needed < BOTTOM) newPage();
    }

    public void title(String text, String sub) throws IOException {
        ensure(60);
        cs.setNonStrokingColor(0.08f, 0.16f, 0.29f);
        cs.addRect(MARGIN - 10, y - 38, WIDTH + 20, 48);
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        text(clean(text), BOLD, 18, MARGIN, y - 18);
        if (sub != null && !sub.isBlank()) text(clean(sub), REG, 9.5f, MARGIN, y - 33);
        cs.setNonStrokingColor(0f, 0f, 0f);
        y -= 62;
    }

    public void h1(String text) throws IOException {
        ensure(40);
        y -= 8;
        cs.setNonStrokingColor(0.08f, 0.16f, 0.29f);
        text(clean(text), BOLD, 13, MARGIN, y);
        cs.setStrokingColor(0.8f, 0.83f, 0.9f);
        cs.moveTo(MARGIN, y - 4);
        cs.lineTo(MARGIN + WIDTH, y - 4);
        cs.stroke();
        cs.setNonStrokingColor(0f, 0f, 0f);
        y -= 20;
    }

    public void h2(String text) throws IOException {
        ensure(30);
        y -= 4;
        text(clean(text), BOLD, 10.5f, MARGIN, y);
        y -= 15;
    }

    public void p(String text) throws IOException {
        paragraph(text, REG, 9.5f, MARGIN, WIDTH);
        y -= 4;
    }

    public void bullet(String text) throws IOException {
        ensure(14);
        text("-", REG, 9.5f, MARGIN + 4, y);
        paragraph(text, REG, 9.5f, MARGIN + 16, WIDTH - 16);
    }

    public void spacer(float h) {
        y -= h;
    }

    /** colFractions sum to 1. */
    public void table(String[] headers, float[] colFractions, List<String[]> rows) throws IOException {
        drawRow(headers, colFractions, true);
        for (String[] r : rows) drawRow(r, colFractions, false);
        y -= 12;
    }

    private void drawRow(String[] cells, float[] frac, boolean header) throws IOException {
        float size = 8.5f, lead = 11f, pad = 4f;
        PDFont f = header ? BOLD : REG;
        List<List<String>> wrapped = new ArrayList<>();
        int maxLines = 1;
        for (int i = 0; i < cells.length; i++) {
            List<String> l = wrap(clean(cells[i] == null ? "" : cells[i]), f, size, WIDTH * frac[i] - 2 * pad);
            if (l.size() > 14) {
                l = new ArrayList<>(l.subList(0, 14));
                l.set(13, l.get(13) + " ...");
            }
            wrapped.add(l);
            maxLines = Math.max(maxLines, l.size());
        }
        float h = maxLines * lead + 2 * pad;
        if (y - h < BOTTOM) newPage();
        if (header) {
            cs.setNonStrokingColor(0.88f, 0.9f, 0.95f);
            cs.addRect(MARGIN, y - h, WIDTH, h);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);
        }
        float x = MARGIN;
        for (int i = 0; i < cells.length; i++) {
            float cw = WIDTH * frac[i];
            float ty = y - pad - size;
            for (String line : wrapped.get(i)) {
                text(line, f, size, x + pad, ty);
                ty -= lead;
            }
            x += cw;
        }
        cs.setStrokingColor(0.82f, 0.85f, 0.91f);
        cs.moveTo(MARGIN, y - h);
        cs.lineTo(MARGIN + WIDTH, y - h);
        cs.stroke();
        y -= h;
    }

    private void paragraph(String text, PDFont f, float size, float x, float width) throws IOException {
        for (String para : clean(text).split("\n")) {
            for (String line : wrap(para, f, size, width)) {
                ensure(size + 4);
                text(line, f, size, x, y);
                y -= size + 3.5f;
            }
        }
    }

    private void text(String s, PDFont f, float size, float x, float yy) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, yy);
        cs.showText(s);
        cs.endText();
    }

    private static List<String> wrap(String text, PDFont f, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            while (f.getStringWidth(word) / 1000 * size > width && word.length() > 1) {
                int cut = word.length();
                while (cut > 1 && f.getStringWidth(word.substring(0, cut)) / 1000 * size > width) cut--;
                if (cur.length() > 0) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (f.getStringWidth(trial) / 1000 * size > width && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(trial);
            }
        }
        if (cur.length() > 0 || lines.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    /** Standard PDF fonts only know WinAnsi: other characters become '?'. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') b.append('\n');
            else if (c == '\t') b.append("    ");
            else if (c < 32) b.append(' ');
            else if (c < 127 || (c >= 160 && c <= 255)) b.append(c);
            else if (c == '–' || c == '—') b.append('-');
            else if (c == '‘' || c == '’') b.append('\'');
            else if (c == '“' || c == '”') b.append('"');
            else b.append('?');
        }
        return b.toString();
    }

    public void save(Path file) throws IOException {
        footer();
        cs.close();
        cs = null;
        doc.save(file.toFile());
    }

    @Override
    public void close() throws IOException {
        if (cs != null) cs.close();
        doc.close();
    }
}
