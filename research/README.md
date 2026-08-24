# Research — การวิเคราะห์ข้อมูลด้วยโมเดลฟรี (Free / No-Key LLM)

โครงการศึกษาว่า **จะใช้โมเดลภาษาที่ไม่ต้องเสียเงินและไม่ต้องมี API key
มาวิเคราะห์ข้อมูลให้เชื่อถือได้แค่ไหน** และจะ **ประเมินความสำเร็จของแต่ละการวิเคราะห์
เป็นตัวเลข** ได้อย่างไร

## หลักการทำงาน

1. ทุกขั้นตอนที่ได้ข้อสรุปหรือความเป็นไปได้ที่ดี → บันทึกเป็นไฟล์ `.md` เสมอ
2. อ้างอิงจากงานวิจัยจริงพร้อมลิงก์ ไม่เขียนจากความจำ
3. ตัวเลขทุกตัวต้องคำนวณหรือวัดได้ ระบุสมมติฐานกำกับเสมอ
4. แยกให้ชัดว่าอะไรคือ **ทฤษฎี**, อะไรคือ **ผลวัดจริง**, อะไรคือ **การคาดเดา**

## สารบัญ

| ไฟล์ | เนื้อหา | สถานะ |
|---|---|---|
| [`01-theory/01-ensemble-theory.md`](01-theory/01-ensemble-theory.md) | ทฤษฎีแกน: ทำไมหลายโมเดลถึงแม่นกว่าโมเดลเดียว และเมื่อไหร่ที่ไม่จริง | ✅ ฉบับแรก |
| [`01-theory/02-confidence-calibration.md`](01-theory/02-confidence-calibration.md) | วิธีวัด p และ ρ, การ calibrate ความเห็นพ้อง, โปรโตคอลการทดลอง | ✅ ฉบับแรก |
| [`02-providers/01-free-provider-survey.md`](02-providers/01-free-provider-survey.md) | ผลวัดจริง: เจ้าไหน/โมเดลไหนใช้ได้ฟรีจริง | ✅ 7 โมเดลจาก 5 ค่าย |
| [`03-experiments/sim_agreement.py`](03-experiments/sim_agreement.py) | สคริปต์จำลองผลของ ρ ต่อการโหวตและการ calibrate (รันได้เลย) | ✅ |
| `03-experiments/` | การวัดผลจริงกับโมเดลฟรี + ชุดข้อมูลทดสอบภาษาไทย | ⏳ กำลังรัน |
| [`04-frameworks/01-revenue-optimization-blue-book.md`](04-frameworks/01-revenue-optimization-blue-book.md) | 13 วิธีจาก Blue Book + กฎแบ่งงาน 3 ชั้นระหว่างโค้ดกับ LLM | ✅ |
| [`04-frameworks/02-management-theory-model-governance.md`](04-frameworks/02-management-theory-model-governance.md) | DMAIC/BSC/Decision Matrix → SOP กำกับดูแลโมเดล | ✅ |
| [`04-frameworks/03-blueprint-gap-analysis.md`](04-frameworks/03-blueprint-gap-analysis.md) | ตรวจ blueprint ระบบโซลาร์เทียบผลวัดจริง + ช่องโหว่ 5 จุด | ✅ |
| [`05-product/01-chat-analyst-plan.md`](05-product/01-chat-analyst-plan.md) | แผน Chat Analyst ใช้ได้ทุกงาน + สถาปัตยกรรม 3 ชั้น | ✅ |
| [`../mockup/chat-analyst.html`](../mockup/chat-analyst.html) | mockup ใช้งานได้จริง เปิดในเบราว์เซอร์ได้เลย | ✅ v0.1 |

## สถานะปัจจุบัน

ขั้นที่ 1 — **หาทฤษฎี** ✅
ขั้นที่ 2 — **ออกแบบวิธีวัดและ calibration** ✅ (มีสคริปต์จำลองประกอบ)
ขั้นที่ 3 — **สร้างชุดทดสอบไทย 100 ข้อ + สำรวจผู้ให้บริการ** ✅
ขั้นถัดไป — วัด p และ φ ตัวจริงจากการยิง 700 requests
