package com.solarai.voicetranslate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * แอปแปลภาษาด้วยเสียง: ฟังจากไมค์ → ถอดเป็นข้อความ → ให้ Claude แปลเป็นไทย → พูดออกลำโพง
 *
 * หน้าจอทั้งหมดเป็น WebView ที่โหลด assets/index.html (แนวเดียวกับแอป Solar AI ในรีโปนี้)
 * ส่วนงานที่ต้องใช้ API ของ Android — ไมค์, ถอดเสียง, อ่านออกเสียง, เรียก Anthropic API —
 * ทำฝั่ง Java แล้วส่งผลกลับ JavaScript
 */
public class MainActivity extends Activity
        implements SpeechInput.Listener, ThaiSpeaker.Listener {

    private static final int REQ_MIC = 1001;

    private WebView webView;
    private SpeechInput speech;
    private ThaiSpeaker speaker;
    private OnDeviceTranslator onDevice;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** ตั้งค่าเมื่อ JS ขอฟังก่อนได้รับสิทธิ์ไมค์ — จะเริ่มฟังให้ทันทีที่ผู้ใช้กดอนุญาต */
    private String pendingLangTag;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // หน้าเว็บอยู่ใน assets ทั้งหมด และเรียก API ผ่าน Java bridge
        // จึงไม่ต้องเปิดสิทธิ์ข้าม origin ให้ JavaScript (ปลอดภัยกว่า)
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        speech = new SpeechInput(this, this);
        speaker = new ThaiSpeaker(this, this);
        onDevice = new OnDeviceTranslator();

        webView.addJavascriptInterface(new TranslateBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    SpeechInput speech() { return speech; }
    ThaiSpeaker speaker() { return speaker; }
    OnDeviceTranslator onDevice() { return onDevice; }

    void runOnMain(Runnable r) { mainHandler.post(r); }

    // ── สิทธิ์ไมโครโฟน ─────────────────────────────────────────────────────

    boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** ขอสิทธิ์ไมค์ แล้วเริ่มฟังต่อให้เลยถ้าผู้ใช้อนุญาต */
    void requestMicPermission(String thenListenWithLang) {
        pendingLangTag = thenListenWithLang;
        requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;

        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        emit("mic_permission", json("granted", granted));

        // ตอนนี้แอปยังไม่ resume (กล่องขอสิทธิ์เพิ่งปิด) ตัวถอดเสียงบางเครื่อง
        // ปฏิเสธถ้าแอปไม่ได้อยู่หน้าจอ จึงรอไปเริ่มฟังใน onResume
        if (!granted) pendingLangTag = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        String lang = pendingLangTag;
        pendingLangTag = null;
        if (lang != null && hasMicPermission()) speech.start(lang);
    }

    void installTtsData() {
        Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            emit("tts_status", json2("status", "failed", "message",
                    "เปิดหน้าติดตั้งเสียงไม่ได้ — ติดตั้ง Google Text-to-Speech เองจาก Play Store"));
        }
    }

    // ── ส่ง event ไปยัง JavaScript ──────────────────────────────────────────

    void emit(final String event, final JSONObject payload) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                String js = "window._nativeEvent && window._nativeEvent("
                        + JSONObject.quote(event) + "," + JSONObject.quote(payload.toString()) + ")";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    /** คืนผลจากตัวแปล (ในเครื่อง/Gemini/Claude) กลับ JavaScript ด้วย callback เดียวกัน */
    void deliver(final String callbackId, final String result) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                String js = "window._bridgeCallback("
                        + JSONObject.quote(callbackId) + "," + JSONObject.quote(result) + ")";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private static JSONObject json(String k, Object v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
            // คีย์ที่เราตั้งเองไม่มีทาง null
        }
        return o;
    }

    private static JSONObject json2(String k1, Object v1, String k2, Object v2) {
        JSONObject o = json(k1, v1);
        try {
            o.put(k2, v2);
        } catch (JSONException ignored) {
            // เช่นเดียวกับข้างบน
        }
        return o;
    }

    // ── callback จากการถอดเสียง ─────────────────────────────────────────────

    @Override public void onReadyForSpeech() { emit("stt_ready", new JSONObject()); }

    @Override public void onLevel(float level) { emit("stt_level", json("level", (double) level)); }

    @Override public void onPartial(String text) { emit("stt_partial", json("text", text)); }

    @Override
    public void onFinal(String text, String detectedLang,
                        PitchGenderDetector.Gender gender, double medianF0) {
        JSONObject o = json("text", text);
        try {
            o.put("detectedLang", detectedLang == null ? JSONObject.NULL : detectedLang);
            o.put("acousticGender", gender.name().toLowerCase());
            o.put("medianF0", Math.round(medianF0 * 10) / 10.0);
        } catch (JSONException ignored) {
            // คีย์คงที่ ค่าที่ใส่เป็น primitive/string เท่านั้น
        }
        emit("stt_final", o);
    }

    @Override public void onError(int code, String messageTh) {
        emit("stt_error", json2("code", code, "message", messageTh));
    }

    @Override public void onEndOfSpeech() { emit("stt_end", new JSONObject()); }

    // ── callback จากการอ่านออกเสียง ────────────────────────────────────────

    @Override public void onTtsStatus(String status, String message) {
        emit("tts_status", json2("status", status, "message", message));
    }

    @Override public void onSpeakStart(String utteranceId) {
        emit("tts_start", json("id", utteranceId));
    }

    @Override public void onSpeakDone(String utteranceId) {
        emit("tts_done", json("id", utteranceId));
    }

    @Override public void onSpeakError(String utteranceId, String message) {
        emit("tts_error", json2("id", utteranceId, "message", message));
    }

    // ── วงจรชีวิต ──────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        // ปล่อยไมค์และหยุดพูดเมื่อแอปไม่ได้อยู่หน้าจอ
        if (speech != null) speech.cancel();
        if (speaker != null) speaker.stop();
    }

    @Override
    protected void onDestroy() {
        if (speech != null) speech.destroy();
        if (speaker != null) speaker.shutdown();
        if (onDevice != null) onDevice.shutdown();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
