# Solar AI

รีโปนี้มีแอป Android สองตัวที่ใช้ Claude API และแชร์โครงสร้าง build เดียวกัน

| แอป | ทำอะไร |
|---|---|
| **Solar AI** (`:app`) | วิเคราะห์ความคุ้มค่าโครงการโซลาร์ C&I ผ่าน 6-stage pipeline |
| **แปลเสียง** (`:voicetranslate`) | ฟังเสียงจากไมค์ → แปลเป็นไทย → พูดออกลำโพง เลือกเพศเสียงตามผู้พูด · ใช้ฟรีได้โดยไม่ต้องมี API key |

---

## Solar AI

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
├── app/src/main/                         โมดูล :app — แอป Solar AI
│   ├── assets/index.html                 UI + pipeline logic ทั้งหมด
│   ├── java/com/solarai/app/MainActivity.java   WebView + AndroidBridge → Anthropic API
│   ├── res/                              ไอคอนและ strings
│   └── AndroidManifest.xml
├── voicetranslate/src/main/              โมดูล :voicetranslate — แอปแปลเสียง
│   ├── assets/index.html                 UI + logic การแปล/เลือกเพศเสียง
│   ├── java/com/solarai/voicetranslate/  ไมค์, ถอดเสียง, วัด pitch, TTS, bridge
│   └── AndroidManifest.xml
├── shared-assets/vendor/                 Tailwind CSS, Font Awesome, ฟอนต์ (ใช้ร่วมสองแอป)
├── tools/css/                            ตัว generate shared-assets/vendor/tailwind.css
├── gradlew, gradle/                      Gradle wrapper 8.9
├── BUILD_INSTRUCTIONS.md                 วิธี build APK
└── VOICE_TRANSLATE.md                    วิธีใช้และข้อจำกัดของแอปแปลเสียง
.github/workflows/build.yml               CI: build APK ทั้งสองตัวอัตโนมัติทุก push
SolarAI-AndroidProject.zip                ไฟล์ zip ต้นฉบับ (เก็บไว้อ้างอิง)
```

สถาปัตยกรรม: หน้าจอทั้งหมดเป็น WebView ที่โหลด `assets/index.html`
ส่วนการเรียก API ทำฝั่ง Java (`HttpURLConnection`) เพื่อเลี่ยง CORS ของ browser
แล้วส่งผลกลับ JavaScript ผ่าน `window._claudeCallback`

## เริ่มต้น

ดู [SolarAI/BUILD_INSTRUCTIONS.md](SolarAI/BUILD_INSTRUCTIONS.md) — build ได้ทั้งจาก
GitHub Actions (ไม่ต้องติดตั้งอะไร), Android Studio หรือ `./gradlew assembleDebug`

ใช้งานต้องมี Anthropic API Key ของตัวเอง (https://console.anthropic.com)

CSS และฟอนต์ทั้งหมดฝังอยู่ในแอป ไม่พึ่ง CDN — หน้าจอจึงขึ้นครบแม้เน็ตช้า
ต่ออินเทอร์เน็ตเฉพาะตอนเรียก Claude API เท่านั้น

---

## แปลเสียง

ฟังเสียงจากไมค์โทรศัพท์ → ถอดเป็นข้อความด้วย `SpeechRecognizer` ของ Android →
แปลเป็นไทย → อ่านออกเสียงด้วย TextToSpeech ในเครื่อง

**ตัวแปลเลือกได้ 3 แบบ** — ค่าเริ่มต้นคือ **ML Kit ในเครื่อง ฟรีตลอด ไม่ต้องมี API key เลย**
(ครั้งแรกของแต่ละภาษาต้องต่อเน็ตโหลดโมเดล จากนั้นใช้ออฟไลน์ได้) สลับไปใช้
**Gemini ฟรีเทียร์** (ขอ key ฟรี ไม่ต้องผูกบัตร คุณภาพดีกว่า) หรือ **Claude** (เสียเงิน
แปลดีที่สุด) ได้ในหน้าตั้งค่า

**เพศของเสียงพูดไทยเลือกอัตโนมัติ** โดยไล่จากสัญญาณที่น่าเชื่อถือที่สุด: ผู้ใช้ตั้งเอง →
ระดับเสียง (F0) ของผู้พูดจริง (Android 13+ ที่อ่านคลื่นเสียงได้) → ร่องรอยทางภาษาในประโยค
ที่โมเดลเดาให้ (ครับ/ค่ะ, ผม/ดิฉัน, การผันคำตามเพศ — เฉพาะโหมด Gemini/Claude) →
ค่าล่าสุดในเซสชัน หน้าจอบอกเสมอว่าใช้สัญญาณไหน และแตะป้ายเพศเพื่อสลับแล้วพูดซ้ำได้ทันที

ถอดเสียงและอ่านออกเสียงใช้ของที่มีในเครื่อง

รายละเอียด ความแม่นยำที่คาดหวังได้ และข้อจำกัด: [SolarAI/VOICE_TRANSLATE.md](SolarAI/VOICE_TRANSLATE.md)
