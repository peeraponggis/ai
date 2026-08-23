# วิธี Build APK จาก Project นี้

โปรเจกต์นี้พร้อม build แล้ว (มี Gradle wrapper, ไอคอน และ CI ครบ)

## ทางที่ 1: GitHub Actions (ไม่ต้องติดตั้งอะไรเลย — แนะนำ)

workflow อยู่ที่ `.github/workflows/build.yml` (root ของ repo) และจะทำงานอัตโนมัติทุกครั้งที่ push

1. push โค้ดขึ้น GitHub
2. เปิดแท็บ **Actions** → เลือก run ล่าสุด
3. ดาวน์โหลด artifact ชื่อ **solar-ai-debug-apk**
4. แตกไฟล์ zip แล้วโอน `app-debug.apk` ไปติดตั้งบน tablet
   (ต้องเปิด "ติดตั้งแอปจากแหล่งที่ไม่รู้จัก" ในเครื่องก่อน)

สั่ง build เองได้จากแท็บ Actions → **Build APK** → **Run workflow**

## ทางที่ 2: Android Studio

1. ดาวน์โหลด Android Studio: https://developer.android.com/studio
2. เปิดโฟลเดอร์ `SolarAI` นี้ (ไม่ใช่ root ของ repo)
3. รอ Gradle sync (ครั้งแรกใช้เวลา 5-10 นาที)
4. เมนู **Build → Build APK(s)**
5. APK อยู่ที่: `app/build/outputs/apk/debug/app-debug.apk`

## ทางที่ 3: command line

ต้องมี JDK 17 และ Android SDK (ตั้งค่า `ANDROID_HOME` หรือใส่ path ใน `local.properties`)

```bash
cd SolarAI
./gradlew assembleDebug
# ผลลัพธ์: app/build/outputs/apk/debug/app-debug.apk
```

## เวอร์ชันที่ใช้

| ส่วนประกอบ | เวอร์ชัน |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.9 (ผ่าน wrapper) |
| JDK | 17 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 (Android 7.0) |

## การใช้งานแอป

1. เปิดแอป → ใส่ Anthropic API Key (`sk-ant-api03-...`) ในช่องบนสุด
   key จะถูกเก็บใน SharedPreferences ของแอป ไม่ต้องใส่ซ้ำ
2. กรอกค่าไฟ/เดือน, ประเภทกิจการ, ค่าไฟต่อหน่วย, ราคา EPC
3. กด **เริ่มวิเคราะห์** → ระบบจะเรียก Claude 6 ครั้งตามลำดับ
   (Technical Sizing → Financial → Risk → Alternatives → Proposal → Consensus)
4. ต้องต่ออินเทอร์เน็ต — ทั้งการเรียก API และ CSS/ฟอนต์จาก CDN

Model ที่ใช้กำหนดไว้ที่ตัวแปร `CLAUDE_MODEL` ใน `app/src/main/assets/index.html`
(ปัจจุบันคือ `claude-sonnet-5`) เปลี่ยนที่เดียวมีผลทุก stage

## ทำไม APK นี้ถึงเรียก Anthropic API ได้?

Android WebView ใช้ Java `HttpURLConnection` ซึ่ง:
- ✅ ไม่มี CORS restriction (CORS เป็นแค่ browser policy)
- ✅ เรียก api.anthropic.com ได้โดยตรง
- ✅ API Key เก็บใน Android SharedPreferences
- ✅ JavaScript ↔ Java ผ่าน `AndroidBridge` interface

หมายเหตุด้านความปลอดภัย: API Key ถูกเก็บเป็น plain text ใน SharedPreferences
ของแอป (อ่านได้เฉพาะแอปนี้ในเครื่องที่ไม่ root) เหมาะกับการใช้งานส่วนตัว/ภายในองค์กร
ไม่ควรแจกจ่าย APK ที่ฝัง key ให้บุคคลภายนอก
