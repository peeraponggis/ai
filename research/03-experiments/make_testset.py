#!/usr/bin/env python3
"""
สร้างชุดทดสอบภาษาไทยที่ "ตรวจอัตโนมัติได้" — ต้องรู้คำตอบจริงถึงจะวัด p ได้

    python3 make_testset.py > testset_v1.jsonl

3 หมวดตามที่ออกแบบไว้ในเอกสาร 02:
  A numeric  — คำนวณเชิงตัวเลข ตรวจด้วยค่าคลาดเคลื่อนสัมพัทธ์
  B extract  — สกัดค่าจากข้อความ ตรวจด้วยการเทียบหลัง normalize
  C classify — จำแนกหมวด ตรวจด้วยการเทียบป้ายกำกับ

ทุกข้อสร้างจาก template + ค่าที่สุ่มด้วย seed คงที่ → เฉลยคำนวณจากสูตรโดยตรง
จึงไม่มีทางเฉลยผิด (ข้อจำกัด: ข้อสอบเป็นแบบมีแบบแผน ไม่ใช่ข้อความธรรมชาติจากงานจริง)
"""
import json, random, sys

SEED = 20260823
BIZ = ['โรงงานอุตสาหกรรม', 'ห้างสรรพสินค้า', 'โรงแรม', 'โรงพยาบาล', 'คลังสินค้า', 'อาคารสำนักงาน']


def numeric_items(rnd, n=40):
    out = []
    for i in range(n):
        kind = i % 8
        if kind == 0:
            bill, tariff = rnd.randrange(80, 900) * 1000, round(rnd.uniform(3.8, 5.6), 2)
            q = f"ค่าไฟเดือนละ {bill:,} บาท อัตรา {tariff} บาทต่อหน่วย ใช้ไฟกี่หน่วยต่อเดือน (ปัดเป็นจำนวนเต็ม)"
            a = round(bill / tariff)
        elif kind == 1:
            kwp, rate = rnd.randrange(50, 1200, 10), round(rnd.uniform(18, 28), 1)
            q = f"ระบบโซลาร์ {kwp} kWp ราคา EPC {rate} บาทต่อวัตต์ เงินลงทุนรวมกี่บาท"
            a = kwp * 1000 * rate
        elif kind == 2:
            kwp = rnd.randrange(50, 1000, 10)
            q = (f"ระบบ {kwp} kWp ที่ PSH 5.0 ชั่วโมงต่อวัน และ Performance Ratio 80% "
                 f"ผลิตไฟได้กี่ kWh ต่อปี (365 วัน ปัดเป็นจำนวนเต็ม)")
            a = round(kwp * 5.0 * 0.80 * 365)
        elif kind == 3:
            epc, save = rnd.randrange(3, 40) * 1_000_000, rnd.randrange(5, 90) * 100_000
            q = f"เงินลงทุน {epc:,} บาท ประหยัดได้ปีละ {save:,} บาท คืนทุนกี่ปี (ทศนิยม 2 ตำแหน่ง)"
            a = round(epc / save, 2)
        elif kind == 4:
            price = rnd.randrange(1000, 900000)
            q = f"ราคาสินค้า {price:,} บาท ยังไม่รวม VAT 7% ราคารวม VAT เท่ากับกี่บาท (ทศนิยม 2 ตำแหน่ง)"
            a = round(price * 1.07, 2)
        elif kind == 5:
            gen = rnd.randrange(100_000, 3_000_000)
            q = f"ผลิตไฟได้ {gen:,} kWh ต่อปี ที่ค่าการปล่อย 0.4633 kgCO2 ต่อ kWh ลด CO2 ได้กี่ตันต่อปี (ทศนิยม 1 ตำแหน่ง)"
            a = round(gen * 0.4633 / 1000, 1)
        elif kind == 6:
            old, new = rnd.randrange(100, 5000), rnd.randrange(100, 5000)
            q = f"ค่าใช้จ่ายเดิม {old:,} บาท ปัจจุบัน {new:,} บาท เปลี่ยนแปลงกี่เปอร์เซ็นต์ (ติดลบถ้าลดลง ทศนิยม 2 ตำแหน่ง)"
            a = round((new - old) / old * 100, 2)
        else:
            kwp = rnd.randrange(50, 900, 10)
            q = f"ระบบ {kwp} kWp ใช้แผงขนาด 550 วัตต์ ต้องใช้กี่แผง (ปัดขึ้นเป็นจำนวนเต็ม)"
            a = -(-kwp * 1000 // 550)
        out.append({'id': f'A{i:03d}', 'set': 'numeric', 'question': q, 'answer': a, 'type': 'number'})
    return out


def extract_items(rnd, n=30):
    out = []
    for i in range(n):
        no = f"QT-{rnd.randrange(2020, 2027)}-{rnd.randrange(1000, 9999)}"
        amount = rnd.randrange(50_000, 9_000_000)
        day, month, year = rnd.randrange(1, 29), rnd.randrange(1, 13), rnd.randrange(2565, 2570)
        cust = rnd.choice(['บริษัท สยามพัฒนา จำกัด', 'ห้างหุ้นส่วนจำกัด ไทยรุ่งเรือง',
                           'บริษัท เอเชียเทค จำกัด (มหาชน)', 'บริษัท นครชัยการช่าง จำกัด'])
        kwp = rnd.randrange(50, 900, 10)
        doc = (f"ใบเสนอราคาเลขที่ {no} วันที่ {day}/{month}/{year}\n"
               f"ลูกค้า: {cust}\n"
               f"รายการ: ติดตั้งระบบโซลาร์รูฟท็อป ขนาด {kwp} kWp\n"
               f"ราคารวมทั้งสิ้น {amount:,} บาท (ยังไม่รวมภาษีมูลค่าเพิ่ม)\n"
               f"ยืนราคา 30 วัน เงื่อนไขชำระ 50/40/10")
        field, ans = [('เลขที่ใบเสนอราคา', no), ('ราคารวมทั้งสิ้น (ตัวเลขอย่างเดียว)', amount),
                      ('ขนาดระบบเป็น kWp (ตัวเลขอย่างเดียว)', kwp), ('ชื่อลูกค้า', cust)][i % 4]
        out.append({'id': f'B{i:03d}', 'set': 'extract',
                    'question': f"จากเอกสารนี้ ให้ตอบเฉพาะ {field}\n\n---\n{doc}\n---",
                    'answer': ans, 'type': 'number' if isinstance(ans, int) else 'text'})
    return out


def classify_items(rnd, n=30):
    templates = [
        ('โรงงานอุตสาหกรรม', ['ไลน์ผลิตเดินสามกะ เครื่องอัดอากาศทำงานตลอดเวลา', 'มีเตาหลอมและระบบสายพานลำเลียง']),
        ('โรงแรม', ['ห้องพัก 180 ห้อง แขกเช็คอินช่วงบ่าย ระบบทำน้ำร้อนใช้ตลอดคืน', 'มีห้องอาหารและสระว่ายน้ำ']),
        ('โรงพยาบาล', ['ห้องผ่าตัดต้องมีไฟสำรอง เครื่อง MRI ใช้ไฟสูง', 'หอผู้ป่วยในเปิดแอร์ตลอด 24 ชั่วโมง']),
        ('คลังสินค้า', ['พื้นที่จัดเก็บ 12,000 ตารางเมตร ใช้รถโฟล์คลิฟท์ไฟฟ้า', 'ห้องเย็นเก็บสินค้าแช่แข็ง']),
        ('อาคารสำนักงาน', ['พนักงาน 400 คน ทำงานจันทร์ถึงศุกร์ 8 โมงถึง 5 โมงเย็น', 'ลิฟต์ 4 ตัวและระบบปรับอากาศส่วนกลาง']),
        ('ห้างสรรพสินค้า', ['ร้านค้าเช่า 120 ยูนิต เปิด 10 โมงถึง 3 ทุ่มทุกวัน', 'โซนซูเปอร์มาร์เก็ตมีตู้แช่จำนวนมาก']),
    ]
    out = []
    for i in range(n):
        label, sents = templates[i % len(templates)]
        text = rnd.choice(sents)
        out.append({'id': f'C{i:03d}', 'set': 'classify',
                    'question': (f"จัดประเภทกิจการจากคำอธิบายต่อไปนี้ "
                                 f"เลือกหนึ่งคำตอบจาก: {', '.join(BIZ)}\n\n{text}"),
                    'answer': label, 'type': 'label'})
    return out


if __name__ == '__main__':
    rnd = random.Random(SEED)
    items = numeric_items(rnd) + extract_items(rnd) + classify_items(rnd)
    for it in items:
        print(json.dumps(it, ensure_ascii=False))
    print(f"สร้าง {len(items)} ข้อ", file=sys.stderr)
