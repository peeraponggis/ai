package com.solarai.voicetranslate;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * พูดข้อความภาษาไทยออกลำโพงด้วย TextToSpeech ในเครื่อง โดยเลือก "เสียงชาย/เสียงหญิง"
 * ตามที่ชั้นบนสั่งมา
 *
 * ข้อจำกัดของ Android: คลาส {@link Voice} <b>ไม่มีฟิลด์บอกเพศ</b> และชื่อเสียงของแต่ละ
 * engine ก็ไม่มีรูปแบบมาตรฐาน จึงไม่มีทางรู้เพศของเสียงจากโค้ดได้อย่างน่าเชื่อถือ
 * คลาสนี้จึงทำสองอย่าง:
 *
 * <ul>
 *   <li>ถ้าผู้ใช้จับคู่เสียงไว้ในหน้าตั้งค่าแล้ว (ฟังตัวอย่างเองแล้วเลือก) → ใช้เสียงนั้นตรง ๆ
 *       ซึ่งให้ผลดีที่สุด</li>
 *   <li>ถ้ายังไม่ได้จับคู่ → ใช้เสียงเริ่มต้นของภาษาไทยแล้วปรับระดับเสียงสูง/ต่ำแทน
 *       ({@link TextToSpeech#setPitch}) วิธีนี้หยาบกว่าแต่ได้ยินความต่างทันทีทุกเครื่อง</li>
 * </ul>
 *
 * ตั้งใจไม่เดาเพศจากชื่อเสียง เพราะจะเดาผิดเงียบ ๆ แล้วผู้ใช้หาสาเหตุไม่เจอ
 */
public class ThaiSpeaker {

    private static final String TAG = "VoiceTranslate";
    public static final Locale THAI = new Locale("th", "TH");

    /** ระดับเสียงตอนต้องแยกชาย/หญิงด้วยการปรับ pitch (ใช้เมื่อยังไม่ได้จับคู่เสียง) */
    private static final float PITCH_MALE = 0.85f;
    private static final float PITCH_FEMALE = 1.12f;

    public interface Listener {
        /**
         * @param status  "ready" | "missing_data" | "unsupported" | "failed"
         * @param message ข้อความอธิบายภาษาไทย
         */
        void onTtsStatus(String status, String message);
        void onSpeakStart(String utteranceId);
        void onSpeakDone(String utteranceId);
        void onSpeakError(String utteranceId, String message);
    }

    private final Listener listener;
    private final AudioManager audioManager;

    private TextToSpeech tts;
    private volatile boolean ready;
    private AudioFocusRequest focusRequest;

    /** ชื่อ Voice ที่ผู้ใช้จับคู่ไว้ — null = ยังไม่ได้จับคู่ */
    private String maleVoiceName;
    private String femaleVoiceName;
    private float speechRate = 1.0f;

    public ThaiSpeaker(Context context, Listener listener) {
        this.listener = listener;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.tts = new TextToSpeech(context, this::onInit);
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            listener.onTtsStatus("failed", "เปิดระบบอ่านออกเสียงของเครื่องไม่สำเร็จ");
            return;
        }
        int result = tts.setLanguage(THAI);
        if (result == TextToSpeech.LANG_MISSING_DATA) {
            listener.onTtsStatus("missing_data",
                    "เครื่องยังไม่มีข้อมูลเสียงภาษาไทย — กดปุ่มติดตั้งเพื่อดาวน์โหลด");
            return;
        }
        if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
            listener.onTtsStatus("unsupported",
                    "ระบบอ่านออกเสียงในเครื่องนี้ไม่รองรับภาษาไทย — "
                            + "ลองติดตั้ง Google Text-to-Speech แล้วตั้งเป็นค่าเริ่มต้น");
            return;
        }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                listener.onSpeakStart(utteranceId);
            }
            @Override public void onDone(String utteranceId) {
                abandonFocus();
                listener.onSpeakDone(utteranceId);
            }
            @Override public void onError(String utteranceId) {
                abandonFocus();
                listener.onSpeakError(utteranceId, "อ่านออกเสียงไม่สำเร็จ");
            }
            @Override public void onError(String utteranceId, int errorCode) {
                abandonFocus();
                listener.onSpeakError(utteranceId, "อ่านออกเสียงไม่สำเร็จ (รหัส " + errorCode + ")");
            }
        });

        ready = true;
        listener.onTtsStatus("ready", "พร้อมอ่านออกเสียงภาษาไทย");
    }

    public boolean isReady() {
        return ready;
    }

    public void setVoiceMapping(String male, String female) {
        this.maleVoiceName = emptyToNull(male);
        this.femaleVoiceName = emptyToNull(female);
    }

    public void setSpeechRate(float rate) {
        this.speechRate = Math.max(0.5f, Math.min(2.0f, rate));
    }

    /** รายชื่อเสียงภาษาไทยที่ติดตั้งอยู่ในเครื่อง เพื่อให้ผู้ใช้ฟังแล้วจับคู่เอง */
    public JSONArray listThaiVoices() {
        JSONArray arr = new JSONArray();
        Set<Voice> voices = allVoices();
        if (voices == null) return arr;

        List<Voice> thai = new ArrayList<>();
        for (Voice v : voices) {
            Locale loc = v.getLocale();
            if (loc != null && "th".equalsIgnoreCase(loc.getLanguage())) thai.add(v);
        }
        Collections.sort(thai, new Comparator<Voice>() {
            @Override public int compare(Voice a, Voice b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        for (Voice v : thai) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", v.getName());
                o.put("quality", v.getQuality());
                o.put("network", v.isNetworkConnectionRequired());
                o.put("needsInstall", v.getFeatures() != null && v.getFeatures()
                        .contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
                arr.put(o);
            } catch (JSONException ignored) {
                // ข้ามเสียงตัวที่สร้าง JSON ไม่ได้
            }
        }
        return arr;
    }

    /**
     * พูดข้อความ
     *
     * @param gender "male" | "female" (ค่าอื่นถือเป็นหญิง ซึ่งเป็นค่าเริ่มต้นของเสียงไทยส่วนใหญ่)
     */
    public void speak(String text, String gender, String utteranceId) {
        if (!ready || tts == null) {
            listener.onSpeakError(utteranceId, "ระบบอ่านออกเสียงยังไม่พร้อม");
            return;
        }
        if (text == null || text.trim().isEmpty()) return;

        boolean male = "male".equalsIgnoreCase(gender);
        Voice picked = findVoice(male ? maleVoiceName : femaleVoiceName);

        if (picked != null) {
            tts.setVoice(picked);
            tts.setPitch(1.0f);          // เสียงถูกจับคู่ไว้แล้ว ไม่ต้องดัดเสียงซ้ำ
        } else {
            tts.setLanguage(THAI);
            tts.setPitch(male ? PITCH_MALE : PITCH_FEMALE);
        }
        tts.setSpeechRate(speechRate);

        requestFocus();
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (r != TextToSpeech.SUCCESS) {
            abandonFocus();
            listener.onSpeakError(utteranceId, "สั่งอ่านออกเสียงไม่สำเร็จ");
        }
    }

    /** อ่านประโยคตัวอย่างด้วยเสียงที่ระบุ เพื่อให้ผู้ใช้ฟังก่อนจับคู่ */
    public void preview(String voiceName, String sample) {
        if (!ready || tts == null) return;
        Voice v = findVoice(voiceName);
        if (v != null) tts.setVoice(v); else tts.setLanguage(THAI);
        tts.setPitch(1.0f);
        tts.setSpeechRate(speechRate);
        requestFocus();
        tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview");
    }

    public void stop() {
        if (tts != null) tts.stop();
        abandonFocus();
    }

    public void shutdown() {
        abandonFocus();
        ready = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private Set<Voice> allVoices() {
        if (!ready || tts == null) return null;
        try {
            return tts.getVoices();
        } catch (Exception e) {          // บาง engine โยน exception จากเมธอดนี้
            Log.w(TAG, "อ่านรายชื่อเสียงไม่ได้: " + e);
            return null;
        }
    }

    /** คืน null ถ้าหาไม่เจอ (เสียงที่จับคู่ไว้อาจถูกถอนการติดตั้งไปแล้ว) */
    private Voice findVoice(String name) {
        if (name == null) return null;
        Set<Voice> voices = allVoices();
        if (voices == null) return null;
        for (Voice v : voices) {
            if (name.equals(v.getName())) return v;
        }
        return null;
    }

    // ── audio focus: หรี่เสียงแอปอื่นระหว่างพูด แล้วคืนให้เมื่อพูดจบ ─────────

    private void requestFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }
}
