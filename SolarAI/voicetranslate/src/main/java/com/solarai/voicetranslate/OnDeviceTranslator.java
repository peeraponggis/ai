package com.solarai.voicetranslate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * แปลข้อความด้วย ML Kit ในเครื่อง — ฟรี ไม่ต้องมี API key และทำงาน offline
 * หลังดาวน์โหลดโมเดลของคู่ภาษานั้นครั้งแรก (~30 MB ต่อภาษา)
 *
 * คืนผลเป็น JSON รูปแบบเดียวกับตัวแปลบนคลาวด์ เพื่อให้ฝั่ง JavaScript จัดการเหมือนกันหมด:
 * <pre>{"translation":"...","source_language":"en","speaker_gender":"unknown","gender_evidence":""}</pre>
 * หรือ <pre>{"error":{"message":"..."}}</pre>
 *
 * ข้อจำกัดที่ต่างจากโหมด LLM: ML Kit ไม่ได้เข้าใจบริบท จึง<b>เดาเพศผู้พูดจากร่องรอย
 * ทางภาษาไม่ได้</b> ฟิลด์ speaker_gender จึงเป็น "unknown" เสมอ แล้วให้ชั้นบนไปใช้
 * ผลจากการวัดระดับเสียงหรือค่าที่ผู้ใช้ตั้งเองแทน
 *
 * อีกข้อ: โมเดลของ ML Kit เทรนไว้แปลกับภาษาอังกฤษ คู่ภาษาที่ไม่มีอังกฤษจะแปลอ้อม
 * ผ่านอังกฤษ ทำให้คุณภาพลดลง
 */
public class OnDeviceTranslator {

    private static final String TAG = "VoiceTranslate";

    public interface Callback {
        /** @param json ผลลัพธ์หรือ error — รูปแบบเดียวกับตัวแปลบนคลาวด์ */
        void onResult(String json);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** cache ตัวแปลตามคู่ภาษา เพราะการสร้างใหม่ทุกครั้งเสียเวลาโหลดโมเดลเข้าหน่วยความจำ */
    private final Map<String, Translator> clients = new HashMap<>();
    private LanguageIdentifier identifier;

    /**
     * @param srcTag  BCP-47 ของภาษาต้นทาง หรือ "auto" ให้ ML Kit เดาจากข้อความเอง
     * @param destTag BCP-47 ของภาษาปลายทาง (ปกติ "th")
     */
    public void translate(final String text, final String srcTag,
                          final String destTag, final Callback cb) {
        main.post(new Runnable() {
            @Override public void run() { start(text, srcTag, destTag, cb); }
        });
    }

    private void start(String text, String srcTag, String destTag, final Callback cb) {
        final String dest = TranslateLanguage.fromLanguageTag(primary(destTag));
        if (dest == null) {
            cb.onResult(error("ML Kit ไม่รองรับภาษาปลายทางนี้"));
            return;
        }

        boolean auto = srcTag == null || srcTag.isEmpty() || "auto".equals(srcTag);
        if (!auto) {
            run(text, primary(srcTag), dest, cb);
            return;
        }

        identifier().identifyLanguage(text)
                .addOnSuccessListener(tag -> {
                    if (tag == null || "und".equals(tag)) {
                        cb.onResult(error("เดาภาษาต้นทางไม่ออก — ลองเลือกภาษาให้ตรงในหน้าหลัก"));
                    } else {
                        run(text, tag, dest, cb);
                    }
                })
                .addOnFailureListener(e ->
                        cb.onResult(error("ตรวจภาษาต้นทางไม่สำเร็จ: " + reason(e))));
    }

    private void run(String text, String srcTagPrimary, final String dest, final Callback cb) {
        final String src = TranslateLanguage.fromLanguageTag(primary(srcTagPrimary));
        if (src == null) {
            cb.onResult(error("ตัวแปลในเครื่องไม่รองรับภาษา \"" + srcTagPrimary
                    + "\" — เลือกภาษาอื่น หรือสลับไปใช้ Gemini/Claude ในหน้าตั้งค่า"));
            return;
        }
        if (src.equals(dest)) {          // พูดภาษาปลายทางอยู่แล้ว ไม่ต้องแปล
            cb.onResult(success(text, src));
            return;
        }

        final Translator translator = client(src, dest);
        // ไม่บังคับ Wi-Fi เพราะจะค้างเงียบ ๆ เมื่อผู้ใช้ใช้เน็ตมือถือ
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> translator.translate(text)
                        .addOnSuccessListener(out -> cb.onResult(success(out, src)))
                        .addOnFailureListener(e ->
                                cb.onResult(error("แปลไม่สำเร็จ: " + reason(e)))))
                .addOnFailureListener(e -> {
                    // โมเดลยังไม่มีในเครื่องและโหลดไม่ได้ — เกือบทุกครั้งคือไม่ได้ต่อเน็ต
                    clients.remove(key(src, dest));
                    translator.close();
                    cb.onResult(error("ดาวน์โหลดโมเดลภาษาไม่สำเร็จ — "
                            + "ครั้งแรกของแต่ละภาษาต้องต่ออินเทอร์เน็ต (" + reason(e) + ")"));
                });
    }

    private Translator client(String src, String dest) {
        String k = key(src, dest);
        Translator t = clients.get(k);
        if (t == null) {
            t = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(dest)
                    .build());
            clients.put(k, t);
        }
        return t;
    }

    private LanguageIdentifier identifier() {
        if (identifier == null) identifier = LanguageIdentification.getClient();
        return identifier;
    }

    public void shutdown() {
        for (Translator t : clients.values()) t.close();
        clients.clear();
        if (identifier != null) {
            identifier.close();
            identifier = null;
        }
    }

    // ── helper ─────────────────────────────────────────────────────────────

    private static String key(String src, String dest) {
        return src + ">" + dest;
    }

    /** "en-US" → "en" (ML Kit รับเฉพาะ subtag แรก) */
    private static String primary(String tag) {
        if (tag == null) return "";
        int dash = tag.indexOf('-');
        return dash > 0 ? tag.substring(0, dash) : tag;
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static String success(String translation, String sourceLanguage) {
        JSONObject o = new JSONObject();
        try {
            o.put("translation", translation);
            o.put("source_language", sourceLanguage);
            // ML Kit ไม่ได้อ่านบริบท จึงไม่มีข้อมูลเรื่องเพศผู้พูดให้
            o.put("speaker_gender", "unknown");
            o.put("gender_evidence", "");
        } catch (JSONException e) {
            Log.w(TAG, "สร้าง JSON ผลแปลไม่ได้: " + e);
        }
        return o.toString();
    }

    private static String error(String message) {
        return "{\"error\":{\"message\":" + JSONObject.quote(message) + "}}";
    }
}
