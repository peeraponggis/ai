# Solar AI

**C&I Solar Dynamic Calculation Engine** — แอป Android วิเคราะห์ความคุ้มค่าโครงการ
โซลาร์เชิงพาณิชย์และอุตสาหกรรม (C&I) ในไทย **คำนวณด้วยสูตรจริงในเครื่อง
ไม่ต้องต่ออินเทอร์เน็ต ไม่ต้องใช้ API key ไม่มีค่าใช้จ่าย**

กรอกค่าไฟ/เดือน + ประเภทกิจการ → กดวิเคราะห์ → ได้ผลครบใน 6 stage

| Stage | ได้อะไร |
|---|---|
| 1. Technical Parser & Sizing | ขนาด kWp, ผลิตไฟ/ปี, % ทดแทนค่าไฟ, ข้อกำหนดหม้อแปลง |
| 2. Financial Modeler | IRR (Newton-Raphson), NPV @8%, payback, ROI 25 ปี |
| 3. Risk & Compliance Auditor | ความเสี่ยง grid / โครงสร้าง / การเงิน + โอกาส |
| 4. Auto-Correction Engine | 4 ทางเลือก: Cost Optimized / Hybrid+ESS / PPA / Phased |
| 5. Sales Proposal Synthesizer | ข้อเสนอขาย + CO₂ ต่อปี |
| 6. Consensus Judge | ตรวจ IRR / Payback / NPV แล้วสรุปคำตัดสิน |

สูตรทั้งหมดเปิดเผยอยู่ในหน้าแอป (กล่อง `// สูตรคำนวณหลัก`)

## ดาวน์โหลด

APK ล่าสุดอยู่ที่หน้า [Releases](https://github.com/peeraponggis/ai/releases) —
โหลดไฟล์ `.apk` จาก tablet ได้เลย ไม่ต้อง login

## โครงสร้าง

```
SolarAI/                                  โปรเจกต์ Android (เปิดโฟลเดอร์นี้ใน Android Studio)
├── app/src/main/
│   ├── assets/index.html                 UI + เครื่องคำนวณทั้งหมด
│   ├── assets/vendor/                    Tailwind CSS, Font Awesome, ฟอนต์ (ฝังในแอป)
│   ├── java/com/solarai/app/MainActivity.java   WebView ที่โหลด index.html
│   ├── res/                              ไอคอนและ strings
│   └── AndroidManifest.xml               ไม่ขอสิทธิ์ใด ๆ แม้แต่ INTERNET
├── tools/css/                            ตัว generate vendor/tailwind.css
├── gradlew, gradle/                      Gradle wrapper 8.9
└── BUILD_INSTRUCTIONS.md                 วิธี build APK
.github/workflows/build.yml               CI: build APK ทุก push
.github/workflows/release.yml             CI: ปล่อย Release พร้อมไฟล์ APK
```

แอปไม่ขอสิทธิ์ INTERNET เลย — ตรวจสอบได้จาก `AndroidManifest.xml` ว่าไม่มีการ
เชื่อมต่อออกนอกเครื่องแม้แต่ทางเดียว
