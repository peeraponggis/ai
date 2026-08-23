package com.solarai.voicetranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * ทดสอบตัวประมาณเพศจากระดับเสียงด้วยคลื่นสังเคราะห์ — รันบน JVM ล้วน ไม่ต้องใช้เครื่องจริง
 *
 * ใช้คลื่นที่มีฮาร์มอนิกหลายชั้นแทนไซน์บริสุทธิ์ เพราะใกล้เคียงเสียงสายเสียงมนุษย์
 * และเป็นเคสที่ตัวประมาณ F0 พลาดเป็น octave ได้ง่ายกว่า
 */
public class PitchGenderDetectorTest {

    private static final int SR = 16000;

    private static short[] voiceLike(double f0Hz, double seconds, double amplitude) {
        int n = (int) (SR * seconds);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            // ฮาร์มอนิก 1-5 ที่แอมพลิจูดลดหลั่น คล้ายสเปกตรัมของเสียงสระ
            double v = 0;
            for (int h = 1; h <= 5; h++) v += Math.sin(2 * Math.PI * f0Hz * h * t) / h;
            out[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, v * amplitude));
        }
        return out;
    }

    private static PitchGenderDetector.Result analyze(short[] pcm) {
        PitchGenderDetector d = new PitchGenderDetector(SR);
        d.feed(pcm, pcm.length);
        return d.result();
    }

    @Test
    public void lowPitchedVoiceIsMale() {
        PitchGenderDetector.Result r = analyze(voiceLike(120, 1.5, 8000));
        assertEquals(PitchGenderDetector.Gender.MALE, r.gender);
        assertEquals(120.0, r.medianF0, 6.0);
    }

    @Test
    public void highPitchedVoiceIsFemale() {
        PitchGenderDetector.Result r = analyze(voiceLike(210, 1.5, 8000));
        assertEquals(PitchGenderDetector.Gender.FEMALE, r.gender);
        assertEquals(210.0, r.medianF0, 8.0);
    }

    /** ช่วงคาบเกี่ยว 155-180 Hz ต้องตอบ UNKNOWN แทนที่จะเดามั่ว */
    @Test
    public void ambiguousPitchIsUnknown() {
        assertEquals(PitchGenderDetector.Gender.UNKNOWN, analyze(voiceLike(168, 1.5, 8000)).gender);
    }

    @Test
    public void whiteNoiseIsUnknown() {
        Random rnd = new Random(42);
        short[] noise = new short[SR];
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (rnd.nextGaussian() * 4000);
        assertEquals(PitchGenderDetector.Gender.UNKNOWN, analyze(noise).gender);
    }

    @Test
    public void silenceIsUnknown() {
        PitchGenderDetector.Result r = analyze(new short[SR]);
        assertEquals(PitchGenderDetector.Gender.UNKNOWN, r.gender);
        assertEquals(0, r.voicedFrames);
    }

    /** เสียงสั้นเกินไป (ไม่ถึงจำนวนเฟรมขั้นต่ำ) ต้องไม่ตัดสิน */
    @Test
    public void tooShortIsUnknown() {
        assertEquals(PitchGenderDetector.Gender.UNKNOWN, analyze(voiceLike(120, 0.2, 8000)).gender);
    }

    /** ป้อนเป็นก้อนไบต์ขนาดไม่เท่ากันต้องได้ผลเท่ากับป้อนทีเดียว (ทดสอบ buffer คร่อมเฟรม) */
    @Test
    public void chunkedByteFeedMatchesSingleFeed() {
        short[] pcm = voiceLike(120, 1.5, 8000);
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            bytes[2 * i] = (byte) (pcm[i] & 0xFF);
            bytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }

        PitchGenderDetector d = new PitchGenderDetector(SR);
        int off = 0, chunk = 1234;      // ขนาดที่หารกับ FRAME_SIZE ไม่ลงตัว
        while (off < bytes.length) {
            int len = Math.min(chunk, bytes.length - off);
            byte[] part = new byte[len];
            System.arraycopy(bytes, off, part, 0, len);
            d.feed(part, len);
            off += len;
        }

        PitchGenderDetector.Result chunked = d.result();
        PitchGenderDetector.Result whole = analyze(pcm);
        assertEquals(whole.gender, chunked.gender);
        assertTrue(Math.abs(whole.medianF0 - chunked.medianF0) < 2.0);
    }
}
