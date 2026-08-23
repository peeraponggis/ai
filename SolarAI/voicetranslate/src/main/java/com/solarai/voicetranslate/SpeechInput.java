package com.solarai.voicetranslate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * ห่อ {@link SpeechRecognizer} ของ Android ให้ใช้ง่ายขึ้น และพยายามดักคลื่นเสียงดิบ
 * ไปวัด pitch เพื่อเดาเพศผู้พูดไปด้วยในคราวเดียว
 *
 * ปกติ SpeechRecognizer ยึดไมค์ไว้คนเดียว แอปจึงไม่เห็นคลื่นเสียง — คลาสนี้จึงไล่ลอง
 * ตามลำดับ:
 *
 * <ol>
 *   <li><b>ชั้น A (Android 13+ ที่มีตัวถอดเสียงในเครื่อง)</b> — แอปอัดเสียงเอง
 *       ด้วย {@link AudioRecord} แล้วป้อน PCM ให้ตัวถอดเสียงผ่าน
 *       {@link RecognizerIntent#EXTRA_AUDIO_SOURCE} → ได้ทั้งข้อความและคลื่นเสียง</li>
 *   <li><b>ชั้น B (ทุกเครื่อง)</b> — ใช้ตัวถอดเสียงตามปกติ และรับคลื่นเสียงจาก
 *       {@link RecognitionListener#onBufferReceived} แบบฉวยโอกาส
 *       (spec มีเมธอดนี้ แต่ตัวถอดเสียงของ Google มักไม่เรียก — ถ้าไม่เรียกก็ไม่เสียอะไร)</li>
 * </ol>
 *
 * ถ้าไม่ได้คลื่นเสียงเลย จะคืนเพศเป็น {@code unknown} แล้วให้ชั้นบนไปใช้วิธีอื่นแทน
 */
public class SpeechInput {

    private static final String TAG = "VoiceTranslate";
    private static final int SAMPLE_RATE = 16000;

    public interface Listener {
        void onReadyForSpeech();
        /** ระดับความดัง 0..1 สำหรับวาดกราฟระหว่างพูด */
        void onLevel(float level);
        void onPartial(String text);
        /**
         * @param detectedLang ภาษาที่ระบบตรวจได้ (Android 13+) หรือ null
         * @param gender       ผลเดาเพศจากคลื่นเสียง
         * @param medianF0     ค่ามัธยฐาน F0 (Hz) — 0 ถ้าวัดไม่ได้
         */
        void onFinal(String text, String detectedLang,
                     PitchGenderDetector.Gender gender, double medianF0);
        /** @param code รหัส error ของ SpeechRecognizer (-1 ถ้าเป็น error ของเราเอง) */
        void onError(int code, String messageTh);
        void onEndOfSpeech();
    }

    private final Context context;
    private final Listener listener;

    private SpeechRecognizer recognizer;
    private PitchGenderDetector detector;
    private String detectedLang;
    private boolean listening;

    /** true = รอบนี้ใช้ชั้น A (เราป้อนเสียงเอง) */
    private boolean feedingAudio;
    /** กันการถอยไปชั้น B ซ้ำ ๆ ไม่รู้จบ */
    private boolean triedFallback;
    private String pendingLangTag;

    private AudioRecord recorder;
    private Thread pumpThread;
    private ParcelFileDescriptor pipeRead;
    private ParcelFileDescriptor pipeWrite;

    public SpeechInput(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public boolean isListening() {
        return listening;
    }

    public static boolean isAvailable(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    /**
     * เริ่มฟัง — ต้องเรียกจาก main thread
     *
     * @param langTag BCP-47 เช่น "en-US" หรือ null/"auto" เพื่อให้ระบบเดาเอง (Android 13+)
     */
    public void start(String langTag) {
        if (listening) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError(-1, "แอปยังไม่ได้รับสิทธิ์ใช้ไมโครโฟน");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(-1, "เครื่องนี้ไม่มีบริการถอดเสียงเป็นข้อความ "
                    + "ลองติดตั้ง/เปิดใช้แอป Google");
            return;
        }

        pendingLangTag = langTag;
        triedFallback = false;
        detectedLang = null;
        detector = new PitchGenderDetector(SAMPLE_RATE);
        launch(langTag, canFeedAudio());
    }

    public void stop() {
        if (recognizer != null) recognizer.stopListening();
        stopRecorder();
    }

    public void cancel() {
        listening = false;
        if (recognizer != null) recognizer.cancel();
        stopRecorder();
    }

    public void destroy() {
        listening = false;
        stopRecorder();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    // ── การเริ่มรอบหนึ่ง ────────────────────────────────────────────────────

    /**
     * ชั้น A ใช้ได้เมื่อเป็น Android 13+ และมีตัวถอดเสียงในเครื่องจริง ๆ
     * (API 31/32 มี EXTRA_AUDIO_SOURCE แต่ยังเช็คความพร้อมไม่ได้ จึงไม่เสี่ยง)
     */
    @SuppressLint("NewApi")
    private boolean canFeedAudio() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
    }

    @SuppressLint("NewApi")   // เรียก API 31+ เฉพาะตอน canFeedAudio() เป็นจริง (Android 13+)
    private void launch(String langTag, boolean withOwnAudio) {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        stopRecorder();

        feedingAudio = withOwnAudio;
        Intent intent = buildIntent(langTag);

        try {
            if (withOwnAudio) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                attachOwnAudio(intent);
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            }
        } catch (Exception e) {
            Log.w(TAG, "สร้างตัวถอดเสียงไม่สำเร็จ: " + e);
            if (withOwnAudio && !triedFallback) {
                triedFallback = true;
                launch(langTag, false);
            } else {
                listener.onError(-1, "เริ่มการถอดเสียงไม่สำเร็จ: " + e.getClass().getSimpleName());
            }
            return;
        }

        recognizer.setRecognitionListener(new Callbacks());
        listening = true;
        recognizer.startListening(intent);
    }

    private Intent buildIntent(String langTag) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

        boolean auto = langTag == null || langTag.isEmpty() || "auto".equals(langTag);
        if (!auto) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag);
        }
        // ให้ระบบบอกภาษาที่ตรวจได้ (Android 13+ เท่านั้น รุ่นเก่าจะเมิน extra นี้ไปเฉย ๆ)
        intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
        return intent;
    }

    /** เปิดไมค์เอง แล้วต่อท่อ PCM เข้าตัวถอดเสียง พร้อมแยกไปวัด pitch */
    private void attachOwnAudio(Intent intent) throws IOException {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new IOException("AudioRecord ไม่รองรับ 16 kHz mono");
        int bufSize = Math.max(minBuf, 4096);

        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize * 2);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("เปิดไมค์ไม่สำเร็จ");
        }

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        pipeRead = pipe[0];
        pipeWrite = pipe[1];

        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeRead);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);

        final AudioRecord rec = recorder;
        final ParcelFileDescriptor out = pipeWrite;
        recorder.startRecording();

        pumpThread = new Thread(() -> {
            byte[] buf = new byte[bufSize];
            try (FileOutputStream os = new FileOutputStream(out.getFileDescriptor())) {
                while (!Thread.currentThread().isInterrupted()
                        && rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    int read = rec.read(buf, 0, buf.length);
                    if (read <= 0) break;
                    os.write(buf, 0, read);
                    PitchGenderDetector d = detector;
                    if (d != null) d.feed(buf, read);
                    listener.onLevel(levelOf(buf, read));
                }
            } catch (IOException e) {
                Log.d(TAG, "ท่อเสียงปิดแล้ว: " + e.getMessage());
            }
        }, "mic-pump");
        pumpThread.start();
    }

    private static float levelOf(byte[] pcm16le, int length) {
        long sumSq = 0;
        int n = length / 2;
        for (int i = 0; i < n; i++) {
            int s = (pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8);
            sumSq += (long) s * s;
        }
        if (n == 0) return 0;
        double rms = Math.sqrt((double) sumSq / n);
        return (float) Math.min(1.0, rms / 8000.0);
    }

    private void stopRecorder() {
        if (pumpThread != null) {
            pumpThread.interrupt();
            pumpThread = null;
        }
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (IllegalStateException ignored) {
                // ไมค์ถูกปล่อยไปแล้ว
            }
            recorder.release();
            recorder = null;
        }
        // ปิดปลายเขียนก่อน เพื่อให้ตัวถอดเสียงที่ถือ FD ที่ dup ไว้เห็น EOF และสรุปผล
        pipeWrite = closeQuietly(pipeWrite);
        pipeRead = closeQuietly(pipeRead);
    }

    private static ParcelFileDescriptor closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
                // ปลายท่อถูกปิดไปแล้ว
            }
        }
        return null;
    }

    // ── callback จากตัวถอดเสียง ─────────────────────────────────────────────

    private class Callbacks implements RecognitionListener {

        @Override public void onReadyForSpeech(Bundle params) { listener.onReadyForSpeech(); }

        @Override public void onBeginningOfSpeech() { }

        @Override public void onRmsChanged(float rmsdB) {
            // rmsdB อยู่ราว -2..10 — แปลงเป็น 0..1 หยาบ ๆ พอให้ UI ขยับ
            if (!feedingAudio) listener.onLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
        }

        /** ชั้น B: ถ้าเครื่องไหนใจดีส่งคลื่นเสียงมาให้ ก็เอาไปวัด pitch ฟรี ๆ */
        @Override public void onBufferReceived(byte[] buffer) {
            PitchGenderDetector d = detector;
            if (!feedingAudio && d != null && buffer != null) d.feed(buffer, buffer.length);
        }

        @Override public void onEndOfSpeech() {
            stopRecorder();
            listener.onEndOfSpeech();
        }

        @Override public void onError(int error) {
            listening = false;
            stopRecorder();

            // ชั้น A ล้มเหลว (ภาษายังไม่ได้ดาวน์โหลด / ตัวถอดเสียงในเครื่องไม่พร้อม)
            // → ถอยไปใช้ตัวถอดเสียงปกติหนึ่งครั้ง โดยผู้ใช้ไม่ต้องรู้เรื่อง
            if (feedingAudio && !triedFallback && isRetryable(error)) {
                triedFallback = true;
                Log.i(TAG, "ชั้น A ล้มเหลว (error " + error + ") — ถอยไปใช้ตัวถอดเสียงปกติ");
                launch(pendingLangTag, false);
                return;
            }
            listener.onError(error, describe(error));
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            stopRecorder();

            ArrayList<String> texts =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (texts == null || texts.isEmpty()) ? "" : texts.get(0);

            PitchGenderDetector.Result r = detector != null
                    ? detector.result()
                    : new PitchGenderDetector(SAMPLE_RATE).result();

            if (text.trim().isEmpty()) {
                listener.onError(SpeechRecognizer.ERROR_NO_MATCH, describe(SpeechRecognizer.ERROR_NO_MATCH));
            } else {
                listener.onFinal(text, detectedLang, r.gender, r.medianF0);
            }
        }

        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> texts =
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (texts != null && !texts.isEmpty()) listener.onPartial(texts.get(0));
        }

        /** Android 13+ เท่านั้น — รุ่นเก่าไม่เรียกเมธอดนี้ */
        @Override public void onLanguageDetection(Bundle results) {
            String lang = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
            if (lang != null && !lang.isEmpty()) detectedLang = lang;
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    }

    private static boolean isRetryable(int error) {
        return error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
                || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_SERVER
                || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
    }

    private static String describe(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "อ่านเสียงจากไมค์ไม่ได้";
            case SpeechRecognizer.ERROR_CLIENT:
                return "ตัวถอดเสียงมีปัญหา ลองใหม่อีกครั้ง";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "ยังไม่ได้ให้สิทธิ์ใช้ไมโครโฟน";
            case SpeechRecognizer.ERROR_NETWORK:
                return "เครือข่ายมีปัญหา — การถอดเสียงภาษานี้อาจต้องต่อเน็ต";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "เครือข่ายตอบช้าเกินไป";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "ไม่ได้ยินคำพูด ลองพูดใกล้ไมค์ขึ้นอีกนิด";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "ตัวถอดเสียงกำลังทำงานอื่นอยู่ รอสักครู่";
            case SpeechRecognizer.ERROR_SERVER:
                return "เซิร์ฟเวอร์ถอดเสียงมีปัญหา";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "ไม่ได้ยินเสียงพูด";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "เครื่องนี้ยังไม่รองรับภาษาที่เลือก — ลองเปลี่ยนภาษาต้นทาง "
                        + "หรือดาวน์โหลดภาษาเพิ่มในการตั้งค่าของ Google";
            default:
                return "ถอดเสียงไม่สำเร็จ (รหัส " + error + ")";
        }
    }
}
