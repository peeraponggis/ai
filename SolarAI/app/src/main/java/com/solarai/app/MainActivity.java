package com.solarai.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String TAG = "SolarAI";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String PREFS = "solar_prefs";
    private static final String KEY_API = "api_key";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // ── JavaScript Bridge ──────────────────────────────────────────────
        // แทนที่ fetch() → ให้ Android เรียก Anthropic API แทน (ไม่มี CORS)
        webView.addJavascriptInterface(new AnthropicBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // โหลด HTML จาก assets
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ── Bridge Class ───────────────────────────────────────────────────────
    class AnthropicBridge {

        // บันทึก API Key ใน SharedPreferences
        @JavascriptInterface
        public void saveApiKey(String key) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putString(KEY_API, key).apply();
        }

        // ดึง API Key
        @JavascriptInterface
        public String getApiKey() {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            return prefs.getString(KEY_API, "");
        }

        // เรียก Anthropic API จาก Native (ไม่มี CORS!)
        // callbackId ใช้คืนผลกลับไปยัง JavaScript
        @JavascriptInterface
        public void callClaude(final String jsonBody, final String apiKey, final String callbackId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
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
                        conn.setConnectTimeout(30000);
                        conn.setReadTimeout(120000);

                        // ส่ง request body
                        OutputStream os = conn.getOutputStream();
                        try {
                            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } finally {
                            os.close();
                        }

                        // อ่าน response (2xx = getInputStream, ที่เหลือ = getErrorStream)
                        int statusCode = conn.getResponseCode();
                        InputStream stream = (statusCode >= 200 && statusCode < 300)
                                ? conn.getInputStream()
                                : conn.getErrorStream();

                        result = readAll(stream);
                        if (result.isEmpty()) {
                            result = errorJson("HTTP " + statusCode + " (empty response)");
                        }
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg == null) msg = e.getClass().getSimpleName();
                        Log.e(TAG, "API Error: " + msg, e);
                        result = errorJson(msg);
                    } finally {
                        if (conn != null) conn.disconnect();
                    }

                    deliver(callbackId, result);
                }
            }).start();
        }
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

    // คืนผลกลับ JavaScript บน Main Thread
    // ใช้ JSONObject.quote() สร้าง string literal ที่ escape ถูกต้องเสมอ
    private void deliver(final String callbackId, final String result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String js = "window._claudeCallback("
                        + JSONObject.quote(callbackId) + ","
                        + JSONObject.quote(result) + ")";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
