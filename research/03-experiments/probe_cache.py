#!/usr/bin/env python3
"""ตรวจว่า gateway ส่งคำตอบเดียวกันให้ทุกโมเดลหรือไม่

เหตุที่ต้องตรวจ: ในชุดวัด classify ทั้ง deepseek-v4-flash และ gpt-oss-20b
ตอบ "โรงพยาบาล" ให้ทุกโจทย์ที่คำอธิบายสั้น รวมถึง "มีเตาหลอมและระบบ
สายพานลำเลียง" ซึ่งไม่กำกวมเลย โมเดลคนละค่ายตอบผิดเหมือนกันเป๊ะทุกข้อ
เป็นลายเซ็นของการแคชร่วม ไม่ใช่ของความสามารถโมเดล

ถ้าจริง แปลว่าที่วัด phi = +0.916 มานั้นไม่ได้วัดความเห็นพ้องของโมเดล
แต่วัดการที่ได้คำตอบก้อนเดียวกันกลับมา และแนวคิดใช้หลายโมเดลช่วยกัน
ตรวจก็ใช้ไม่ได้ตั้งแต่ต้น

วิธี:
  1. ยิงโจทย์เดียวกันไปทุกโมเดล — ถ้าตอบเหมือนกันทุกตัวอักษร น่าสงสัย
  2. ยิงโจทย์เดิมแต่เติมรหัสสุ่มท้ายข้อความ — ถ้าคำตอบเปลี่ยน แปลว่าแคช
  3. ยิงโจทย์เดียวกันซ้ำสองครั้งกับโมเดลเดียว — ดูว่าได้ก้อนเดิมไหม
"""
import json, sys, time, urllib.request, urllib.error

ENDPOINT = 'https://api.llm7.io/v1/chat/completions'
MODELS = ['DeepSeek-V4-Flash-0731', 'gpt-oss:20b', 'gemini-3.1-flash-lite',
          'mistral-Nemo-Instruct-2407', 'minimax-m2.7']
SYSTEM = ('คุณเป็นผู้ช่วยวิเคราะห์ข้อมูล ตอบเป็นภาษาไทย '
          'อธิบายสั้นที่สุดเท่าที่จำเป็น แล้วปิดท้ายด้วยบรรทัดสุดท้ายในรูปแบบ '
          '"ANSWER: <คำตอบ>" โดย <คำตอบ> ต้องเป็นค่าเดียวสั้น ๆ ไม่มีหน่วย ไม่มีเครื่องหมายคั่นหลักพัน')
BIZ = 'โรงงานอุตสาหกรรม, ห้างสรรพสินค้า, โรงแรม, โรงพยาบาล, คลังสินค้า, อาคารสำนักงาน'
BASE = f'จัดประเภทกิจการจากคำอธิบายต่อไปนี้ เลือกหนึ่งคำตอบจาก: {BIZ}\n\nมีเตาหลอมและระบบสายพานลำเลียง'


def ask(model, question, timeout=30):
    body = json.dumps({'model': model, 'temperature': 0, 'max_tokens': 120,
                       'messages': [{'role': 'system', 'content': SYSTEM},
                                    {'role': 'user', 'content': question}]}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, method='POST', headers={
        'Content-Type': 'application/json', 'Authorization': 'Bearer unused'})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            j = json.load(r)
            hdr = {k.lower(): v for k, v in r.headers.items()}
            return (j['choices'][0]['message']['content'].strip(),
                    j.get('model'), hdr.get('x-llm-gateway-cache', '-'))
    except Exception as e:
        return (f'ERR {e}', None, '-')


def show(tag, model, q):
    txt, served, cache = ask(model, q)
    one = txt.replace('\n', ' ')[:70]
    print(f'{tag:26s} {model:30s} cache={cache:6s} served={served}')
    print(f'{"":26s} → {one!r}', flush=True)
    time.sleep(8)
    return txt


print('=== 1) โจทย์เดียวกัน ยิงทุกโมเดล ===', flush=True)
same = {m: show('โจทย์เดิม', m, BASE) for m in MODELS}

print('\n=== 2) โจทย์เดิม + รหัสกันแคชท้ายข้อความ ===', flush=True)
busted = {m: show('เติมรหัสกันแคช', m, BASE + f'\n(อ้างอิง {hash(m) % 99991})') for m in MODELS}

print('\n=== 3) ยิงซ้ำโมเดลเดิม โจทย์เดิม ===', flush=True)
show('ยิงซ้ำครั้งที่ 2', MODELS[0], BASE)

print('\n=== สรุป ===')
uniq = len({v for v in same.values() if not v.startswith('ERR')})
got = len([v for v in same.values() if not v.startswith('ERR')])
print(f'โจทย์เดิม: {got} โมเดลตอบได้ ได้คำตอบต่างกัน {uniq} แบบ')
if got >= 2 and uniq == 1:
    print('>> ทุกโมเดลคืนข้อความเดียวกันเป๊ะ — เข้าข่ายแคชร่วมหรือ route ไป backend เดียว')
changed = sum(1 for m in MODELS
              if not same[m].startswith('ERR') and not busted[m].startswith('ERR')
              and same[m] != busted[m])
print(f'เติมรหัสกันแคชแล้วคำตอบเปลี่ยน {changed} จาก {got} โมเดล')
if changed >= max(1, got - 1):
    print('>> คำตอบเปลี่ยนเมื่อข้อความเปลี่ยน — ชี้ไปที่การแคชตามเนื้อความ')
