# Solar AI

**C&I Solar AI Engine** — แอป Android สำหรับวิเคราะห์ความคุ้มค่าของโครงการโซลาร์
เชิงพาณิชย์และอุตสาหกรรม (C&I) ในไทย โดยเรียก Claude API ผ่าน 6-stage pipeline

| Stage | Agent | ผลลัพธ์ |
|---|---|---|
| 1 | Technical Sizing | ขนาดระบบ kWp, จำนวนแผง, พื้นที่, การผลิตไฟ/ปี |
| 2 | Financial Modeler | EPC, ประหยัด/ปี, payback, IRR, NPV, ROI 25 ปี |
| 3 | Risk Auditor | ความเสี่ยง HIGH/MED/LOW + วิธีรับมือ |
| 4 | Auto-Correction | 4 ทางเลือก: Cost Optimized / Hybrid+ESS / PPA / Phased |
| 5 | Proposal Synthesizer | ข้อเสนอขาย + CO₂ reduction |
| 6 | Consensus Judge | ตรวจความสอดคล้อง + คำตัดสินลงทุน |

## โครงสร้าง

```
SolarAI/                                  โปรเจกต์ Android (เปิดโฟลเดอร์นี้ใน Android Studio)
├── app/src/main/
│   ├── assets/index.html                 UI + pipeline logic ทั้งหมด
│   ├── java/com/solarai/app/MainActivity.java   WebView + AndroidBridge → Anthropic API
│   ├── res/                              ไอคอนและ strings
│   └── AndroidManifest.xml
├── gradlew, gradle/                      Gradle wrapper 8.9
└── BUILD_INSTRUCTIONS.md                 วิธี build APK
.github/workflows/build.yml               CI: build APK อัตโนมัติทุก push
SolarAI-AndroidProject.zip                ไฟล์ zip ต้นฉบับ (เก็บไว้อ้างอิง)
```

สถาปัตยกรรม: หน้าจอทั้งหมดเป็น WebView ที่โหลด `assets/index.html`
ส่วนการเรียก API ทำฝั่ง Java (`HttpURLConnection`) เพื่อเลี่ยง CORS ของ browser
แล้วส่งผลกลับ JavaScript ผ่าน `window._claudeCallback`

## เริ่มต้น

ดู [SolarAI/BUILD_INSTRUCTIONS.md](SolarAI/BUILD_INSTRUCTIONS.md) — build ได้ทั้งจาก
GitHub Actions (ไม่ต้องติดตั้งอะไร), Android Studio หรือ `./gradlew assembleDebug`

ใช้งานต้องมี Anthropic API Key ของตัวเอง (https://console.anthropic.com)
