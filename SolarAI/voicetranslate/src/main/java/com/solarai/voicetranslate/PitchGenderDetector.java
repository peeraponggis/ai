package com.solarai.voicetranslate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ประมาณเพศของผู้พูดจากระดับเสียงพื้นฐาน (F0 / pitch) ของคลื่นเสียงดิบ
 *
 * วิธี: ซอยเสียงเป็นเฟรมสั้น ๆ → หา F0 ของแต่ละเฟรมด้วย YIN (cumulative mean
 * normalized difference function) → ทิ้งเฟรมที่เบาเกินไปหรือไม่ใช่เสียงก้อง
 * (unvoiced เช่น ส/ฟ/ช) → ใช้ค่ามัธยฐานของ F0 ที่เหลือมาตัดสิน
 *
 * ข้อจำกัดที่ต้องรู้: วิธีนี้แม่นราว 85% กับเสียงผู้ใหญ่ในที่เงียบ และพลาดได้กับ
 * เสียงเด็ก เสียงกระซิบ ผู้ชายเสียงสูง ผู้หญิงเสียงทุ้ม และห้องที่มีเสียงรบกวน
 * จึงต้องให้ผู้ใช้แก้ผลเองได้เสมอ และคืน {@link Gender#UNKNOWN} เมื่อไม่มั่นใจ
 *
 * คลาสนี้ไม่พึ่ง API ของ Android เลย เพื่อให้เขียน unit test แบบ JVM ล้วนได้
 */
public final class PitchGenderDetector {

    public enum Gender { MALE, FEMALE, UNKNOWN }

    /** ช่วง F0 ที่สนใจ (Hz) — ครอบคลุมเสียงพูดของมนุษย์เกือบทั้งหมด */
    private static final int MIN_F0_HZ = 60;
    private static final int MAX_F0_HZ = 400;

    private static final int FRAME_SIZE = 1024;   // 64 ms ที่ 16 kHz
    private static final int HOP_SIZE   = 512;

    /** ค่า d'(τ) ที่ยอมรับว่าเฟรมนั้นเป็นเสียงก้อง (ยิ่งน้อยยิ่งมั่นใจ) */
    private static final double VOICED_MAX_CMND = 0.45;
    /** ระดับความดังขั้นต่ำสัมบูรณ์ (RMS ของ PCM16) กันเฟรมเงียบ/ลมหายใจ */
    private static final double ABS_RMS_FLOOR = 300.0;
    /** ระดับความดังขั้นต่ำเทียบกับเฟรมที่ดังที่สุดของประโยค */
    private static final double REL_RMS_FLOOR = 0.20;
    /** ต้องมีเฟรมก้องอย่างน้อยเท่านี้ ไม่งั้นถือว่าข้อมูลน้อยเกินไปจะตัดสิน */
    private static final int MIN_VOICED_FRAMES = 12;

    /** เกณฑ์ตัดสิน (Hz) — ช่วงระหว่างสองค่านี้คือคาบเกี่ยว ตอบ UNKNOWN */
    private static final double MALE_MAX_F0   = 155.0;
    private static final double FEMALE_MIN_F0 = 180.0;

    /** กันหน่วยความจำบานปลายถ้ามีคนกดค้างไว้นานมาก (~2 นาทีที่ 16 kHz) */
    private static final int MAX_FRAMES = 4000;

    public static final class Result {
        public final Gender gender;
        /** ค่ามัธยฐานของ F0 (Hz) — 0 ถ้าไม่มีเฟรมก้องเลย */
        public final double medianF0;
        public final int voicedFrames;

        Result(Gender gender, double medianF0, int voicedFrames) {
            this.gender = gender;
            this.medianF0 = medianF0;
            this.voicedFrames = voicedFrames;
        }
    }

    private final int sampleRate;
    private final int minLag;
    private final int maxLag;

    private final short[] pending = new short[FRAME_SIZE];
    private int pendingCount = 0;

    private final List<double[]> frames = new ArrayList<>(); // [f0Hz, rms]

    public PitchGenderDetector(int sampleRate) {
        this.sampleRate = sampleRate;
        this.minLag = Math.max(2, sampleRate / MAX_F0_HZ);
        this.maxLag = Math.min(FRAME_SIZE / 2, sampleRate / MIN_F0_HZ);
    }

    /** ป้อน PCM 16-bit little-endian mono (รูปแบบที่ AudioRecord คืนมา) */
    public void feed(byte[] pcm16le, int length) {
        int samples = length / 2;
        short[] buf = new short[samples];
        for (int i = 0; i < samples; i++) {
            buf[i] = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
        }
        feed(buf, samples);
    }

    /** ป้อนตัวอย่างเสียงเป็น short (ค่า PCM16) */
    public void feed(short[] pcm, int length) {
        int offset = 0;
        while (offset < length) {
            int copy = Math.min(FRAME_SIZE - pendingCount, length - offset);
            System.arraycopy(pcm, offset, pending, pendingCount, copy);
            pendingCount += copy;
            offset += copy;

            if (pendingCount == FRAME_SIZE) {
                analyzeFrame(pending);
                // เลื่อนหน้าต่างไปข้างหน้า 1 hop (เฟรมซ้อนกันครึ่งหนึ่ง)
                System.arraycopy(pending, HOP_SIZE, pending, 0, FRAME_SIZE - HOP_SIZE);
                pendingCount = FRAME_SIZE - HOP_SIZE;
            }
        }
    }

    /** สรุปผลจากทุกเฟรมที่ป้อนเข้ามา (เรียกซ้ำได้ ไม่ล้างสถานะ) */
    public Result result() {
        double maxRms = 0;
        for (double[] f : frames) maxRms = Math.max(maxRms, f[1]);

        double floor = Math.max(ABS_RMS_FLOOR, maxRms * REL_RMS_FLOOR);
        List<Double> f0s = new ArrayList<>();
        for (double[] f : frames) {
            if (f[0] > 0 && f[1] >= floor) f0s.add(f[0]);
        }

        if (f0s.size() < MIN_VOICED_FRAMES) {
            return new Result(Gender.UNKNOWN, 0, f0s.size());
        }

        Collections.sort(f0s);
        double median = f0s.size() % 2 == 1
                ? f0s.get(f0s.size() / 2)
                : (f0s.get(f0s.size() / 2 - 1) + f0s.get(f0s.size() / 2)) / 2.0;

        Gender g;
        if (median < MALE_MAX_F0) g = Gender.MALE;
        else if (median > FEMALE_MIN_F0) g = Gender.FEMALE;
        else g = Gender.UNKNOWN;   // คาบเกี่ยว — ไม่เดามั่ว

        return new Result(g, median, f0s.size());
    }

    public void reset() {
        frames.clear();
        pendingCount = 0;
    }

    // ── การประมาณ F0 ของหนึ่งเฟรมด้วย YIN ────────────────────────────────

    private void analyzeFrame(short[] frame) {
        if (frames.size() >= MAX_FRAMES) return;

        double sumSq = 0;
        for (short s : frame) sumSq += (double) s * s;
        double rms = Math.sqrt(sumSq / frame.length);

        double f0 = (rms < ABS_RMS_FLOOR) ? 0 : estimateF0(frame);
        frames.add(new double[]{ f0, rms });
    }

    /** คืน F0 เป็น Hz หรือ 0 ถ้าเฟรมนี้ไม่ใช่เสียงก้อง */
    private double estimateF0(short[] x) {
        int window = x.length - maxLag;          // จำนวนตัวอย่างที่ใช้เทียบต่อหนึ่ง lag
        double[] diff = new double[maxLag + 1];

        for (int tau = 1; tau <= maxLag; tau++) {
            double sum = 0;
            for (int i = 0; i < window; i++) {
                double d = x[i] - x[i + tau];
                sum += d * d;
            }
            diff[tau] = sum;
        }

        // cumulative mean normalized difference — ทำให้ค่าเทียบข้าม tau ได้
        double[] cmnd = new double[maxLag + 1];
        cmnd[0] = 1;
        double running = 0;
        for (int tau = 1; tau <= maxLag; tau++) {
            running += diff[tau];
            cmnd[tau] = running == 0 ? 1 : diff[tau] * tau / running;
        }

        // เลือก tau แรกที่ต่ำกว่าเกณฑ์และเป็นก้นหลุม (กัน octave error จากการ
        // ไปเจอค่าต่ำสุดที่ tau เป็นสองเท่าของคาบจริง)
        int best = -1;
        for (int tau = minLag; tau <= maxLag; tau++) {
            if (cmnd[tau] < VOICED_MAX_CMND
                    && cmnd[tau] <= cmnd[tau - 1]
                    && (tau + 1 > maxLag || cmnd[tau] <= cmnd[tau + 1])) {
                best = tau;
                break;
            }
        }
        if (best < 0) return 0;

        // ประมาณจุดต่ำสุดแบบต่อเนื่องด้วยพาราโบลาจาก 3 จุดรอบ ๆ
        double refined = best;
        if (best > minLag && best < maxLag) {
            double a = cmnd[best - 1], b = cmnd[best], c = cmnd[best + 1];
            double denom = 2 * (2 * b - a - c);
            if (denom != 0) refined = best + (c - a) / denom;
        }

        double f0 = sampleRate / refined;
        return (f0 >= MIN_F0_HZ && f0 <= MAX_F0_HZ) ? f0 : 0;
    }
}
