package com.api.automation.otp;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Soft two-note chime (like a phone notification) played ONCE when the OTP popup opens.
 * Low volume, smooth fade-in / fade-out - no harsh system beep. Never blocks the test run.
 * Turn off with otp.sound=false.
 */
public final class Chime {

    private Chime() {}

    public static void play() {
        if (!com.api.automation.config.ConfigManager.getBool("otp.sound", true)) return;
        Thread t = new Thread(Chime::render, "otp-chime");
        t.setDaemon(true);
        t.start();
    }

    private static void render() {
        float rate = 44100f;
        double volume = Math.max(0.02, Math.min(0.6, com.api.automation.config.ConfigManager.getInt("otp.sound.volume", 20) / 100.0));
        try (SourceDataLine line = AudioSystem.getSourceDataLine(new AudioFormat(rate, 16, 1, true, false))) {
            line.open();
            line.start();
            byte[] a = tone(rate, 880.0, 180, volume);     // A5
            byte[] b = tone(rate, 1318.5, 320, volume);    // E6 - rising, friendly
            line.write(a, 0, a.length);
            line.write(b, 0, b.length);
            line.drain();
        } catch (Exception e) {
            java.awt.Toolkit.getDefaultToolkit().beep();   // no sound device available
        }
    }

    /** Sine tone with a soft attack and an exponential fade, so it sounds gentle. */
    private static byte[] tone(float rate, double freq, int ms, double volume) {
        int n = (int) (rate * ms / 1000);
        byte[] out = new byte[n * 2];
        int attack = (int) (rate * 0.012);
        for (int i = 0; i < n; i++) {
            double env = (i < attack ? i / (double) attack : 1.0) * Math.exp(-3.0 * i / n);
            double s = Math.sin(2 * Math.PI * freq * i / rate) * 0.85 + Math.sin(4 * Math.PI * freq * i / rate) * 0.15;
            short v = (short) (s * env * volume * Short.MAX_VALUE);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }
}
