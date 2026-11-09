package com.api.product.ai;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * Detects what this PC can run and recommends a local model size. The assistant never depends on the model; this only
 * decides which free open-source model is worth installing. When the PC changes (more RAM, a real GPU) the recommendation
 * and the model Studio picks change automatically.
 */
public final class HardwareProfile {

    /** Catalog in ascending quality / cost. Sizes are approximate download sizes (GB, 4-bit). */
    public static final String[] MODELS = { "qwen2.5:1.5b", "qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b" };
    public static final double[] SIZE_GB = { 1.0, 1.9, 4.7, 9.0 };

    public final double ramGb;
    public final int cores;
    public final String gpuName;
    public final double gpuVramGb;
    public final int tier;
    public final String reason;

    private HardwareProfile(double ramGb, int cores, String gpuName, double gpuVramGb) {
        this.ramGb = ramGb;
        this.cores = cores;
        this.gpuName = gpuName;
        this.gpuVramGb = gpuVramGb;
        int t;
        String why;
        if (gpuVramGb >= 12 || ramGb >= 48) {
            t = 3;
            why = "plenty of memory (" + fmt(Math.max(gpuVramGb, ramGb)) + " GB)";
        } else if (ramGb >= 16 && (gpuVramGb >= 6 || cores >= 12)) {
            t = 2;
            why = gpuVramGb >= 6 ? "a GPU with " + fmt(gpuVramGb) + " GB can run a 7B model" : cores + " CPU cores can run a 7B model";
        } else if (ramGb >= 8) {
            t = 1;
            why = fmt(ramGb) + " GB RAM and " + (gpuVramGb >= 4 ? "a small GPU" : "no usable GPU") + ": a 3B model runs acceptably on the CPU";
        } else {
            t = 0;
            why = "only " + fmt(ramGb) + " GB RAM: only a very small model fits";
        }
        this.tier = t;
        this.reason = why;
    }

    public String recommendedModel() {
        return MODELS[tier];
    }

    public static int tierOf(String model) {
        for (int i = MODELS.length - 1; i >= 0; i--) {
            if (model != null && model.toLowerCase().startsWith(MODELS[i].toLowerCase())) return i;
        }
        return -1;
    }

    public static HardwareProfile detect() {
        double ram = 8;
        try {
            ram = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / 1073741824.0;
        } catch (Throwable ignored) {
            // keep default
        }
        int cores = Runtime.getRuntime().availableProcessors();
        String gpu = "none";
        double vram = 0;
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                String line = new String(p.getInputStream().readAllBytes()).trim().split("\\R")[0];
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    gpu = parts[0].trim();
                    vram = Double.parseDouble(parts[1].trim()) / 1024.0;
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            // no NVIDIA GPU / driver
        }
        return new HardwareProfile(ram, cores, gpu, vram);
    }

    public String summary() {
        return fmt(ramGb) + " GB RAM, " + cores + " CPU threads, GPU: " + gpuName + (gpuVramGb > 0 ? " (" + fmt(gpuVramGb) + " GB)" : "");
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ENGLISH, "%.0f", d);
    }
}
