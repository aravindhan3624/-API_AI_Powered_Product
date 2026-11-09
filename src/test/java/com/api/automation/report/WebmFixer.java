package com.api.automation.report;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a browser-recorded WebM seekable.
 *
 * Chrome's MediaRecorder writes WebM as a live stream: no total duration, no seek index (Cues), clusters of unknown
 * size. Players then show a "buffering" bar and the timeline cannot be clicked or fast-forwarded. This class rewrites
 * the container (the video itself is copied byte for byte, nothing is re-encoded): it adds Duration, a SeekHead and
 * Cues, and gives every Cluster its real size. No external tool (ffmpeg) is needed.
 */
public final class WebmFixer {

    private static final long ID_EBML = 0x1A45DFA3L, ID_SEGMENT = 0x18538067L, ID_INFO = 0x1549A966L,
            ID_TRACKS = 0x1654AE6BL, ID_CLUSTER = 0x1F43B675L, ID_CUES = 0x1C53BB6BL, ID_SEEKHEAD = 0x114D9B74L,
            ID_TAGS = 0x1254C367L, ID_CHAPTERS = 0x1043A770L, ID_ATTACH = 0x1941A469L;
    private static final long MAX_BYTES = 1_500_000_000L;

    private WebmFixer() {}

    private static final class Cluster {
        int start, end;          // payload range in the input
        long timecode;           // cluster timecode (timescale units)
        long lastBlock;          // highest block time in this cluster (absolute)
    }

    /** Repairs the file in place. @return true when it was rewritten, false when left as it was (already fine / not understood). */
    public static boolean fix(Path webm) {
        return fix(webm, false);
    }

    /** @param keepRaw true = the untouched recording is kept as  &lt;name&gt;_raw.webm  (safety copy until the repair is trusted) */
    public static boolean fix(Path webm, boolean keepRaw) {
        Path tmp = webm.resolveSibling(webm.getFileName() + ".fixing");
        try {
            if (Files.size(webm) < 64 || Files.size(webm) > MAX_BYTES) return false;
            byte[] in;
            try (RandomAccessFile raf = new RandomAccessFile(webm.toFile(), "r")) {
                in = new byte[(int) raf.length()];
                raf.readFully(in);
            }
            if (!rewrite(in, tmp)) {
                Files.deleteIfExists(tmp);
                return false;
            }
            if (keepRaw) {
                Files.copy(webm, webm.resolveSibling(webm.getFileName().toString().replaceAll("(?i)\\.webm$", "") + "_raw.webm"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, webm, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            System.out.println("⚠ Could not make the recording seekable: " + e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            return false;
        }
    }

    private static boolean rewrite(byte[] in, Path out) throws IOException {
        int[] p = {0};
        long id = readId(in, p);
        if (id != ID_EBML) return false;
        int hdrSize = (int) readSize(in, p);
        int ebmlEnd = p[0] + hdrSize;
        long segId = readId(in, new int[]{ebmlEnd});
        if (segId != ID_SEGMENT) return false;
        p[0] = ebmlEnd;
        readId(in, p);
        readSize(in, p);                       // segment size (usually "unknown" for a live recording)
        int segData = p[0];

        byte[] info = null, tracks = null;
        List<Cluster> clusters = new ArrayList<>();
        int pos = segData;
        while (pos < in.length) {
            int[] q = {pos};
            long eid;
            long size;
            try {
                eid = readId(in, q);
                size = readSize(in, q);
            } catch (RuntimeException ex) {
                break;                          // cut-off tail
            }
            int dataStart = q[0];
            if (eid == ID_CLUSTER) {
                Cluster c = new Cluster();
                c.start = dataStart;
                c.end = size < 0 ? scanClusterEnd(in, dataStart) : (int) Math.min(in.length, dataStart + size);
                readClusterTimes(in, c);
                clusters.add(c);
                pos = c.end;
                continue;
            }
            if (size < 0) break;
            int end = (int) Math.min(in.length, dataStart + size);
            if (eid == ID_INFO) info = java.util.Arrays.copyOfRange(in, dataStart, end);
            else if (eid == ID_TRACKS) tracks = java.util.Arrays.copyOfRange(in, dataStart, end);
            pos = end;                          // SeekHead / Void / Tags / old Cues are dropped
        }
        if (info == null || tracks == null || clusters.isEmpty()) return false;

        long scale = 1_000_000L;               // default TimecodeScale (ns per tick)
        byte[] newInfo = rebuildInfo(info, clusters, scale);
        int trackNo = firstTrackNumber(tracks);

        // layout: SeekHead | Info | Tracks | Cues | Clusters  (positions are relative to the segment data start)
        int seekHeadLen = 4 + 1 + 3 * 17;
        int infoLen = 4 + 8 + newInfo.length;
        int tracksLen = 4 + 8 + tracks.length;
        int cuesLen = 4 + 8 + clusters.size() * 19;
        long firstCluster = seekHeadLen + infoLen + tracksLen + cuesLen;

        long total = firstCluster;
        for (Cluster c : clusters) total += 4 + 8 + (c.end - c.start);

        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            os.write(in, 0, ebmlEnd);                                   // EBML header untouched
            writeId(os, ID_SEGMENT);
            writeSize8(os, total);
            // SeekHead
            writeId(os, ID_SEEKHEAD);
            os.write(0x80 | 51);
            writeSeek(os, ID_INFO, seekHeadLen);
            writeSeek(os, ID_TRACKS, seekHeadLen + infoLen);
            writeSeek(os, ID_CUES, seekHeadLen + infoLen + tracksLen);
            // Info + Tracks
            writeId(os, ID_INFO);
            writeSize8(os, newInfo.length);
            os.write(newInfo);
            writeId(os, ID_TRACKS);
            writeSize8(os, tracks.length);
            os.write(tracks);
            // Cues - one entry per cluster
            writeId(os, ID_CUES);
            writeSize8(os, clusters.size() * 19L);
            long off = firstCluster;
            for (Cluster c : clusters) {
                os.write(new byte[]{(byte) 0xBB, (byte) 0x91});
                os.write(new byte[]{(byte) 0xB3, (byte) 0x84});
                os.write(ByteBuffer.allocate(4).putInt((int) Math.min(c.timecode, 0xFFFFFFFFL)).array());
                os.write(new byte[]{(byte) 0xB7, (byte) 0x89, (byte) 0xF7, (byte) 0x81, (byte) trackNo, (byte) 0xF1, (byte) 0x84});
                os.write(ByteBuffer.allocate(4).putInt((int) off).array());
                off += 4 + 8 + (c.end - c.start);
            }
            // Clusters with their real size
            for (Cluster c : clusters) {
                writeId(os, ID_CLUSTER);
                writeSize8(os, c.end - c.start);
                os.write(in, c.start, c.end - c.start);
            }
        }
        return true;
    }

    /** Info without the old Duration, plus the real one. */
    private static byte[] rebuildInfo(byte[] info, List<Cluster> clusters, long scale) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int[] p = {0};
        while (p[0] < info.length) {
            int s = p[0];
            long id = readId(info, p);
            long size = readSize(info, p);
            int end = (int) Math.min(info.length, p[0] + size);
            if (id == 0x4489L) { p[0] = end; continue; }                // old Duration
            if (id == 0x2AD7B1L) {                                      // TimecodeScale
                long v = 0;
                for (int i = p[0]; i < end; i++) v = (v << 8) | (info[i] & 0xFF);
                if (v > 0) scale = v;
            }
            b.write(info, s, end - s);
            p[0] = end;
        }
        long lastTick = 0;
        for (Cluster c : clusters) lastTick = Math.max(lastTick, c.lastBlock);
        double frameTicks = 40_000_000.0 / scale;                       // + one frame (~40 ms) so the end is not cut
        double duration = lastTick + frameTicks;
        b.write(new byte[]{0x44, (byte) 0x89, (byte) 0x88});
        b.write(ByteBuffer.allocate(8).putDouble(duration).array());
        return b.toByteArray();
    }

    private static int firstTrackNumber(byte[] tracks) {
        for (int i = 0; i < tracks.length - 2; i++) {
            if ((tracks[i] & 0xFF) == 0xD7 && (tracks[i + 1] & 0xFF) == 0x81) return tracks[i + 2] & 0x7F;   // TrackNumber, 1 byte
        }
        return 1;
    }

    /** End of a cluster of unknown size: the next top-level element (Cluster / Cues / Tags ...). */
    private static int scanClusterEnd(byte[] in, int start) {
        int pos = start;
        while (pos < in.length) {
            int[] q = {pos};
            try {
                long id = readId(in, q);
                if (id == ID_CLUSTER || id == ID_CUES || id == ID_TAGS || id == ID_INFO || id == ID_TRACKS
                        || id == ID_SEEKHEAD || id == ID_CHAPTERS || id == ID_ATTACH) return pos;
                long size = readSize(in, q);
                if (size < 0 || q[0] + size > in.length) return pos;    // cut-off element: keep only complete ones
                pos = (int) (q[0] + size);
            } catch (RuntimeException e) {
                return pos;
            }
        }
        return in.length;
    }

    private static void readClusterTimes(byte[] in, Cluster c) {
        int pos = c.start;
        long rel = 0;
        List<long[]> blocks = new ArrayList<>();
        while (pos < c.end) {
            int[] q = {pos};
            long id = readId(in, q);
            long size = readSize(in, q);
            int ds = q[0];
            if (size < 0 || ds + size > c.end) break;
            if (id == 0xE7L) {                                         // Timecode
                long v = 0;
                for (int i = ds; i < ds + size; i++) v = (v << 8) | (in[i] & 0xFF);
                c.timecode = v;
            } else if (id == 0xA3L) {                                  // SimpleBlock
                rel = Math.max(rel, blockTime(in, ds));
            } else if (id == 0xA0L) {                                  // BlockGroup -> Block
                int p2 = ds;
                int end2 = (int) (ds + size);
                while (p2 < end2) {
                    int[] r = {p2};
                    long id2 = readId(in, r);
                    long s2 = readSize(in, r);
                    if (s2 < 0 || r[0] + s2 > end2) break;
                    if (id2 == 0xA1L) rel = Math.max(rel, blockTime(in, r[0]));
                    p2 = (int) (r[0] + s2);
                }
            }
            pos = (int) (ds + size);
        }
        c.lastBlock = c.timecode + rel;
    }

    /** relative time of a (Simple)Block: track-number vint, then a signed 16-bit time. */
    private static long blockTime(byte[] in, int ds) {
        int[] q = {ds};
        readSize(in, q);
        return (short) (((in[q[0]] & 0xFF) << 8) | (in[q[0] + 1] & 0xFF));
    }

    // ── EBML primitives ──────────────────────────────────────────────────
    /** Element ID including its length marker bits. */
    private static long readId(byte[] b, int[] p) {
        int first = b[p[0]] & 0xFF;
        int len = Integer.numberOfLeadingZeros(first) - 24 + 1;
        if (len < 1 || len > 4) throw new IllegalStateException("bad id at " + p[0]);
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[p[0] + i] & 0xFF);
        p[0] += len;
        return v;
    }

    /** Data size; -1 = unknown size. */
    private static long readSize(byte[] b, int[] p) {
        int first = b[p[0]] & 0xFF;
        int len = Integer.numberOfLeadingZeros(first) - 24 + 1;
        if (len < 1 || len > 8) throw new IllegalStateException("bad size at " + p[0]);
        long v = first & ((1 << (8 - len)) - 1);
        boolean allOnes = v == ((1 << (8 - len)) - 1);
        for (int i = 1; i < len; i++) {
            int x = b[p[0] + i] & 0xFF;
            v = (v << 8) | x;
            if (x != 0xFF) allOnes = false;
        }
        p[0] += len;
        return allOnes ? -1 : v;
    }

    private static void writeId(OutputStream os, long id) throws IOException {
        int len = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        for (int i = len - 1; i >= 0; i--) os.write((int) (id >> (8 * i)) & 0xFF);
    }

    /** 8-byte size field (0x01 marker + 7 bytes). */
    private static void writeSize8(OutputStream os, long v) throws IOException {
        os.write(0x01);
        for (int i = 6; i >= 0; i--) os.write((int) (v >> (8 * i)) & 0xFF);
    }

    private static void writeSeek(OutputStream os, long id, long position) throws IOException {
        os.write(new byte[]{0x4D, (byte) 0xBB, (byte) 0x8E, 0x53, (byte) 0xAB, (byte) 0x84});
        writeId(os, id);
        os.write(new byte[]{0x53, (byte) 0xAC, (byte) 0x84});
        os.write(ByteBuffer.allocate(4).putInt((int) position).array());
    }
}
