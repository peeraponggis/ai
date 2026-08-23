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

1. เปิดแอป → กรอกค่าไฟเฉลี่ย/เดือน, ประเภทกิจการ, อัตราค่าไฟ, ราคา EPC
2. กด **เริ่มวิเคราะห์** → ได้ผลครบ 6 stage ทันที

ไม่ต้องใส่ API key ไม่ต้องสมัครอะไร ไม่ต้องต่ออินเทอร์เน็ต — สูตรคำนวณทั้งหมด
อยู่ใน `app/src/main/assets/index.html` และแสดงไว้ในหน้าแอปด้วย

## ทำไมถึงไม่ต้องต่อเน็ต

ทุกอย่างอยู่ในไฟล์ APK: หน้าเว็บ, CSS, ฟอนต์ และสูตรคำนวณ (JavaScript)
`MainActivity` ทำหน้าที่แค่เปิด WebView โหลด `file:///android_asset/index.html`
ไม่มีโค้ดเรียกเครือข่ายเหลืออยู่ และ `AndroidManifest.xml` ไม่ได้ขอสิทธิ์
`INTERNET` ด้วยซ้ำ — ต่อให้อยากส่งข้อมูลออกก็ทำไม่ได้

## การเซ็น APK (signing key)

Release ที่ปล่อยจาก tag/branch จะถูกเซ็นด้วย **release key ถาวร** ที่เก็บใน
GitHub Secrets ทำให้ติดตั้งทับเวอร์ชันเดิมได้โดยไม่ต้องถอนแอปออกก่อน

Secrets ที่ต้องมี (ตั้งที่ Settings → Secrets and variables → Actions):

| Secret | ค่า |
|---|---|
| `KEYSTORE_BASE64` | ไฟล์ keystore ที่ผ่าน `base64 -w0` |
| `KEYSTORE_PASSWORD` | รหัสผ่าน keystore |
| `KEY_ALIAS` | ชื่อ alias ของ key |
| `KEY_PASSWORD` | รหัสผ่าน key |

ถ้ายังไม่ได้ตั้ง secret เหล่านี้ workflow จะเตือนแล้วปล่อยเป็น debug build แทน
(ยังติดตั้งได้ แต่อัปเดตทับของเดิมไม่ได้)

**ห้าม commit ไฟล์ keystore หรือรหัสผ่านขึ้น repo** — repo นี้เป็น public
`.gitignore` กัน `*.keystore`, `*.jks` และ `keystore.properties` ไว้แล้ว

### สร้าง keystore ใหม่เอง

```bash
keytool -genkeypair -v -keystore solar-ai-release.keystore -storetype PKCS12 \
  -alias solar-ai -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Solar AI, O=Solar AI, C=TH"
base64 -w0 solar-ai-release.keystore   # เอาผลลัพธ์ไปใส่ KEYSTORE_BASE64
```

เก็บไฟล์ keystore ไว้ให้ดี — ถ้าหายจะอัปเดตแอปทับของเดิมไม่ได้อีกเลย

### build release ในเครื่องตัวเอง

สร้างไฟล์ `SolarAI/keystore.properties` (ถูก gitignore ไว้):

```properties
storeFile=/path/to/solar-ai-release.keystore
storePassword=...
keyAlias=solar-ai
keyPassword=...
```

แล้วสั่ง `./gradlew assembleRelease` — ได้ไฟล์ที่
`app/build/outputs/apk/release/app-release.apk`

### เลขเวอร์ชัน

`versionName` มาจากชื่อ tag (ตัด `v` ออก) และ `versionCode` ใช้เลข run ของ
GitHub Actions ซึ่งเพิ่มขึ้นทุกครั้ง — Android จึงยอมให้ติดตั้งทับได้เสมอ
ตอน build ในเครื่องโดยไม่ตั้ง env จะได้ค่า default คือ versionCode 1 / versionName 1.0
