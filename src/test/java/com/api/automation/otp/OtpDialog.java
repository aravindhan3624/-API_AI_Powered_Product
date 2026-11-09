package com.api.automation.otp;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * SweetAlert-style OTP popup (desktop), always on top of every window (also the browser).
 * Shows only what matters for the OTP: OVD number, where the OTP was sent, the API.
 * Countdown ring (90 s by default); when it reaches 0 the popup closes and the default OTP is used.
 */
public final class OtpDialog {

    // ── Palette ────────────────────────────────────────────────────────────
    private static final Color BG = Color.WHITE;
    private static final Color TEXT = new Color(0x1C2833);
    private static final Color MUTED = new Color(0x6B7785);
    private static final Color BLUE = new Color(0x1565C0);
    private static final Color VIOLET = new Color(0x6C3FC9);
    private static final Color GREEN = new Color(0x1E8449);
    private static final Color AMBER = new Color(0xD68910);
    private static final Color RED = new Color(0xC0392B);
    private static final Color TRACK = new Color(0xE6EAF0);
    private static final Color GREY = new Color(0x95A5A6);

    private final PendingOtp pending;
    private JDialog dialog;
    private Timer timer;
    private Ring ring;
    private JTextField otpField;
    private JLabel statusLabel;
    private JLabel errorLabel;
    private JPanel buttons;
    private Boolean shownExpired;

    private OtpDialog(PendingOtp pending) {
        this.pending = pending;
    }

    public static OtpDialog open(PendingOtp pending) {
        OtpDialog d = new OtpDialog(pending);
        SwingUtilities.invokeLater(d::build);
        return d;
    }

    public void close() {
        SwingUtilities.invokeLater(() -> {
            if (timer != null) timer.stop();
            if (dialog != null) dialog.dispose();
        });
    }

    // ════════════════════════════════════════════════════════════════════
    private void build() {
        if (pending.future.isDone()) return;
        OtpRequest r = pending.request;

        dialog = new JDialog((Frame) null, "OTP Required - " + r.tcId, false);
        dialog.setUndecorated(true);
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel outer = new RoundPanel(BG, 24);
        outer.setLayout(new BorderLayout());

        // ── colourful header ──
        Header header = new Header(r.negative ? AMBER : BLUE, r.negative ? new Color(0xE67E22) : VIOLET);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(new EmptyBorder(16, 26, 18, 26));
        header.add(center(label("🔐", 30, Font.PLAIN, Color.WHITE)));
        header.add(center(label(r.attempt > 1 ? "Enter the OTP again" : "OTP Verification", 22, Font.BOLD, Color.WHITE)));
        header.add(Box.createVerticalStrut(3));
        header.add(center(label(shorten(r.apiName, 60) + "   ·   " + r.tcId, 12, Font.PLAIN, new Color(0xE8EEFF))));
        outer.add(header, BorderLayout.NORTH);

        JPanel root = transparent(null);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(16, 28, 22, 28));

        // ── wrong OTP / negative notes ──
        if (r.error != null && !r.error.isBlank()) {
            root.add(banner("⚠  " + r.error, new Color(0xFBE5E2), RED));
            root.add(Box.createVerticalStrut(10));
        }
        if (r.note != null && !r.note.isBlank()) {
            root.add(banner(r.note, new Color(0xFFF3DC), new Color(0x8A5A00)));
            root.add(Box.createVerticalStrut(10));
        }

        // ── OVD + sent-to cards ──
        JPanel cards = transparent(new GridLayout(1, r.ovd != null ? 2 : 1, 10, 0));
        if (r.ovd != null) cards.add(card((r.ovdLabel == null ? "OVD" : r.ovdLabel).toUpperCase(), r.ovd, new Color(0xF1E9FF), VIOLET));
        cards.add(card("OTP SENT TO · " + (r.targetLabel == null ? "" : r.targetLabel.replace(" (confirmed by API response)", "").toUpperCase()),
                r.target == null ? "-" : r.target, new Color(0xE8F1FC), BLUE));
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        root.add(cards);
        if (r.serverMessage != null && !r.serverMessage.isBlank()) {
            root.add(Box.createVerticalStrut(6));
            root.add(center(label("Server: " + shorten(r.serverMessage, 70), 12, Font.ITALIC, MUTED)));
        }
        root.add(Box.createVerticalStrut(12));

        // ── countdown + input ──
        ring = new Ring();
        JPanel inputRow = transparent(new FlowLayout(FlowLayout.CENTER, 16, 0));
        inputRow.add(ring);
        otpField = new JTextField(8);
        otpField.setFont(new Font("Consolas", Font.BOLD, 30));
        otpField.setHorizontalAlignment(JTextField.CENTER);
        otpField.setForeground(TEXT);
        otpField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(r.attempt > 1 ? RED : new Color(0xB8C4D3), 2, true),
                new EmptyBorder(8, 10, 8, 10)));
        otpField.setPreferredSize(new Dimension(230, 62));
        otpField.addActionListener(e -> submit());
        inputRow.add(otpField);
        root.add(inputRow);

        root.add(Box.createVerticalStrut(8));
        errorLabel = label(" ", 12, Font.BOLD, RED);
        root.add(center(errorLabel));
        statusLabel = label(" ", 13, Font.PLAIN, MUTED);
        root.add(center(statusLabel));
        root.add(Box.createVerticalStrut(12));

        buttons = transparent(new FlowLayout(FlowLayout.CENTER, 12, 0));
        root.add(buttons);
        outer.add(root, BorderLayout.CENTER);

        dialog.setContentPane(outer);
        dialog.getRootPane().registerKeyboardAction(e -> skip(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        makeDraggable(header);

        tick();          // adds the buttons BEFORE sizing, so nothing gets squeezed
        dialog.pack();
        dialog.setSize(Math.max(520, dialog.getWidth()), dialog.getHeight());
        try {
            dialog.setShape(new RoundRectangle2D.Double(0, 0, dialog.getWidth(), dialog.getHeight(), 24, 24));
        } catch (Exception ignored) {
            outer.setBorder(BorderFactory.createLineBorder(new Color(0xB8C4D3)));
        }
        dialog.setLocationRelativeTo(null);

        dialog.setVisible(true);
        bringToFront();
        // Windows sometimes ignores the first request while another app has focus - ask once more
        Timer again = new Timer(700, e -> bringToFront());
        again.setRepeats(false);
        again.start();

        timer = new Timer(200, e -> tick());
        timer.start();
    }

    /** Show above every other window (IntelliJ, browser ...) and put the cursor in the OTP box. */
    private void bringToFront() {
        if (dialog == null || !dialog.isDisplayable()) return;
        dialog.setAlwaysOnTop(false);     // toggling re-raises the window on Windows
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
        dialog.requestFocus();
        otpField.requestFocusInWindow();
    }

    // ── periodic refresh: countdown + time-over state + closed elsewhere ──
    private void tick() {
        if (pending.future.isDone()) {
            close();
            return;
        }
        boolean expired = pending.isExpired();
        ring.update(pending.remainingMs(), pending.request.timeoutSeconds * 1000L);

        if (shownExpired == null || shownExpired != expired) {
            shownExpired = expired;
            buttons.removeAll();
            if (expired) {
                statusLabel.setText("Time over - closing, default OTP " + pending.request.defaultOtp + " will be used");
                statusLabel.setForeground(RED);
                otpField.setEnabled(false);
            } else {
                statusLabel.setForeground(MUTED);
                otpField.setEnabled(true);
                otpField.requestFocusInWindow();
                buttons.add(button("Skip", GREY, this::skip));
                buttons.add(button("Submit OTP", GREEN, this::submit));
            }
            buttons.revalidate();
            buttons.repaint();
        }
        if (!expired) {
            long s = (pending.remainingMs() + 999) / 1000;
            statusLabel.setText("Enter the OTP within " + s + " s  ·  then default " + pending.request.defaultOtp + " is used");
        }
    }

    private void submit() {
        if (pending.isExpired()) return;
        String otp = otpField.getText().trim();
        String err = PendingOtp.validate(otp);
        if (err != null) {
            errorLabel.setText(err);
            otpField.requestFocusInWindow();
            return;
        }
        pending.complete(OtpResult.submit(otp, "desktop popup"));
    }

    private void skip() {
        pending.complete(OtpResult.skip("Skipped by tester"));
    }

    // ════════════════════════════════════════════════════════════════════
    //  UI helpers
    // ════════════════════════════════════════════════════════════════════
    private static JPanel card(String key, String value, Color bg, Color fg) {
        RoundPanel c = new RoundPanel(bg, 14);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBorder(new EmptyBorder(9, 12, 10, 12));
        c.add(center(label(key, 10, Font.BOLD, fg)));
        c.add(Box.createVerticalStrut(3));
        c.add(center(label(value, 19, Font.BOLD, TEXT)));
        return c;
    }

    private static JPanel banner(String text, Color bg, Color fg) {
        RoundPanel b = new RoundPanel(bg, 12);
        b.setLayout(new BorderLayout());
        b.setBorder(new EmptyBorder(8, 12, 8, 12));
        b.add(label("<html><body style='width:400px'>" + text.replace("<", "&lt;") + "</body></html>", 12, Font.BOLD, fg));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        return b;
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", style, size));
        l.setForeground(color);
        return l;
    }

    private static PillButton button(String text, Color bg, Runnable action) {
        PillButton b = new PillButton(text, bg, Color.WHITE);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JPanel center(JComponent c) {
        JPanel p = transparent(new FlowLayout(FlowLayout.CENTER, 0, 0));
        p.add(c);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return p;
    }

    private static JPanel transparent(LayoutManager lm) {
        JPanel p = lm == null ? new JPanel() : new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "-";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private void makeDraggable(JComponent c) {
        Point[] start = new Point[1];
        c.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { start[0] = e.getPoint(); }
        });
        c.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                Point p = dialog.getLocation();
                dialog.setLocation(p.x + e.getX() - start[0].x, p.y + e.getY() - start[0].y);
            }
        });
    }

    /** Gradient header with rounded top corners. */
    private static class Header extends JPanel {
        private final Color a, b;

        Header(Color a, Color b) {
            this.a = a;
            this.b = b;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(0, 0, a, getWidth(), getHeight(), b));
            g2.fillRoundRect(0, 0, getWidth(), getHeight() + 24, 24, 24);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Rounded background panel. */
    private static class RoundPanel extends JPanel {
        private final Color color;
        private final int arc;

        RoundPanel(Color color, int arc) {
            this.color = color;
            this.arc = arc;
            setOpaque(false);
            setAlignmentX(Component.CENTER_ALIGNMENT);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Rounded coloured button. */
    private static class PillButton extends JButton {
        private final Color bg;

        PillButton(String text, Color bg, Color fg) {
            super(text);
            this.bg = bg;
            setForeground(fg);
            setFont(new Font("Segoe UI", Font.BOLD, 14));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(10, 24, 10, 24));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getModel().isRollover() ? bg.darker() : bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Circular countdown. */
    private static class Ring extends JComponent {
        private long remaining;
        private long total = 1;

        Ring() {
            setPreferredSize(new Dimension(84, 84));
        }

        void update(long remainingMs, long totalMs) {
            remaining = remainingMs;
            total = Math.max(1, totalMs);
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int s = Math.min(getWidth(), getHeight()) - 6;
            int x = (getWidth() - s) / 2, y = (getHeight() - s) / 2;

            double frac = remaining / (double) total;
            Color c = remaining == 0 ? RED : frac < 0.25 ? RED : frac < 0.5 ? AMBER : GREEN;

            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 28));
            g2.fill(new Ellipse2D.Double(x, y, s, s));
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(TRACK);
            g2.draw(new Ellipse2D.Double(x + 4, y + 4, s - 8, s - 8));
            g2.setColor(c);
            g2.draw(new Arc2D.Double(x + 4, y + 4, s - 8, s - 8, 90, remaining == 0 ? 360 : 360 * frac, Arc2D.OPEN));

            String big = remaining == 0 ? "0" : String.valueOf((remaining + 999) / 1000);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 26));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(big, (getWidth() - fm.stringWidth(big)) / 2, getHeight() / 2 + fm.getAscent() / 2 - 7);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fm = g2.getFontMetrics();
            g2.setColor(MUTED);
            g2.drawString("sec", (getWidth() - fm.stringWidth("sec")) / 2, getHeight() / 2 + 20);
            g2.dispose();
        }
    }
}
