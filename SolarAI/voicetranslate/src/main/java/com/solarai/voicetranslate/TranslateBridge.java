package com.solarai.voicetranslate;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * สะพานระหว่าง JavaScript ใน WebView กับ API ของ Android
 *
 * ทุกเมธอดที่ติด {@link JavascriptInterface} ถูกเรียกจากเธรดของ WebView ไม่ใช่ main thread
 * งานที่แตะ SpeechRecognizer / TextToSpeech จึงต้อง post กลับไป main thread เสมอ
 */
public class TranslateBridge {

    private static final String TAG = "VoiceTranslate";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String PREFS = "voice_translate_prefs";
    private static final String KEY_API = "api_key";

    private final MainActivity host;

    TranslateBridge(MainActivity host) {
        this.host = host;
    }

    private SharedPreferences prefs() {
        return host.getSharedPreferences(PREFS, MainActivity.MODE_PRIVATE);
    }

    // ── ค่าตั้งต่าง ๆ ───────────────────────────────────────────────────────

    @JavascriptInterface
    public void saveApiKey(String key) {
        prefs().edit().putString(KEY_API, key).apply();
    }

    @JavascriptInterface
    public String getApiKey() {
        return prefs().getString(KEY_API, "");
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
        prefs().edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String getPref(String key, String defaultValue) {
        return prefs().getString(key, defaultValue);
    }

    /** ข้อมูลเครื่อง เพื่อให้ UI อธิบายได้ว่าการเดาเพศชั้นไหนใช้งานได้บ้าง */
    @SuppressLint("NewApi")
    @JavascriptInterface
    public String getCapabilities() {
        JSONObject o = new JSONObject();
        try {
            o.put("sdkInt", Build.VERSION.SDK_INT);
            o.put("recognitionAvailable", SpeechRecognizer.isRecognitionAvailable(host));
            boolean onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(host);
            // ชั้นวัด pitch ใช้ได้เมื่อแอปป้อนเสียงให้ตัวถอดเสียงในเครื่องได้เอง
            o.put("acousticGender", onDevice);
            o.put("languageDetection", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);
            o.put("micPermission", host.hasMicPermission());
        } catch (JSONException ignored) {
            // คีย์คงที่ทั้งหมด
        }
        return o.toString();
    }

    // ── ไมโครโฟน / ถอดเสียง ────────────────────────────────────────────────

    @JavascriptInterface
    public boolean hasMicPermission() {
        return host.hasMicPermission();
    }

    /**
     * เริ่มฟัง ถ้ายังไม่ได้สิทธิ์ไมค์จะขอสิทธิ์ก่อนแล้วเริ่มให้เองเมื่อผู้ใช้อนุญาต
     *
     * @param langTag BCP-47 เช่น "en-US" หรือ "auto"
     */
    @JavascriptInterface
    public void startListening(final String langTag) {
        host.runOnMain(new Runnable() {
            @Override public void run() {
                if (!host.hasMicPermission()) {
                    host.requestMicPermission(langTag);
                    return;
                }
                host.speaker().stop();     // กันเสียงที่แอปกำลังพูดย้อนเข้าไมค์
                host.speech().start(langTag);
            }
        });
    }

    @JavascriptInterface
    public void stopListening() {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speech().stop(); }
        });
    }

    @JavascriptInterface
    public void cancelListening() {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speech().cancel(); }
        });
    }

    // ── อ่านออกเสียง ───────────────────────────────────────────────────────

    @JavascriptInterface
    public String listThaiVoices() {
        return host.speaker().listThaiVoices().toString();
    }

    @JavascriptInterface
    public void setVoiceMapping(final String maleVoice, final String femaleVoice) {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speaker().setVoiceMapping(maleVoice, femaleVoice); }
        });
    }

    @JavascriptInterface
    public void setSpeechRate(final double rate) {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speaker().setSpeechRate((float) rate); }
        });
    }

    /** @param gender "male" หรือ "female" */
    @JavascriptInterface
    public void speak(final String text, final String gender, final String utteranceId) {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speaker().speak(text, gender, utteranceId); }
        });
    }

    @JavascriptInterface
    public void previewVoice(final String voiceName, final String sample) {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speaker().preview(voiceName, sample); }
        });
    }

    @JavascriptInterface
    public void stopSpeaking() {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.speaker().stop(); }
        });
    }

    @JavascriptInterface
    public void installTtsData() {
        host.runOnMain(new Runnable() {
            @Override public void run() { host.installTtsData(); }
        });
    }

    // ── เรียก Anthropic API จากฝั่ง Java (ไม่ติด CORS) ─────────────────────

    @JavascriptInterface
    public void callClaude(final String jsonBody, final String apiKey, final String callbackId) {
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(API_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("x-api-key", apiKey);
                    conn.setRequestProperty("anthropic-version", "2023-06-01");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);

                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } finally {
                        os.close();
                    }

                    // 2xx อ่านจาก getInputStream ที่เหลืออ่านจาก getErrorStream
                    int statusCode = conn.getResponseCode();
                    InputStream stream = (statusCode >= 200 && statusCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

                    result = readAll(stream);
                    if (result.isEmpty()) {
                        result = errorJson("HTTP " + statusCode + " (ไม่มีเนื้อหาตอบกลับ)");
                    }
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg == null) msg = e.getClass().getSimpleName();
                    Log.e(TAG, "API Error: " + msg, e);
                    result = errorJson(msg);
                } finally {
                    if (conn != null) conn.disconnect();
                }
                host.deliver(callbackId, result);
            }
        }).start();
    }

    private static String readAll(InputStream stream) throws java.io.IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    private static String errorJson(String message) {
        return "{\"error\":{\"message\":" + JSONObject.quote(message) + "}}";
    }
}
