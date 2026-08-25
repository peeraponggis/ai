#!/usr/bin/env python3
"""หาสาเหตุ HTTP 400 ของ gpt-oss:20b ในหมวดงานเขียน

gpt-oss ตอบ 400 Bad Request ทั้ง 18 ครั้งในรอบวัดงานเขียน แต่เคยตอบ 60/60
ได้ในหมวดอื่น ต่างกันสองอย่าง: system prompt คนละชุด และคำถามยาวกว่า
ทดสอบทีละตัวแปรเพื่อแยกว่าอะไรทำให้ 400 และเก็บ body ของ error มาดูด้วย
"""
import json, time, urllib.request, urllib.error

URL = 'https://api.llm7.io/v1/chat/completions'
MODEL = 'gpt-oss:20b'
SYS_STD = ('คุณเป็นผู้ช่วยวิเคราะห์ข้อมูล ตอบเป็นภาษาไทย '
           'อธิบายสั้นที่สุดเท่าที่จำเป็น แล้วปิดท้ายด้วยบรรทัดสุดท้ายในรูปแบบ '
           '"ANSWER: <คำตอบ>" โดย <คำตอบ> ต้องเป็นค่าเดียวสั้น ๆ ไม่มีหน่วย ไม่มีเครื่องหมายคั่นหลักพัน')
SYS_WRITE = ('คุณเป็นผู้ช่วยเขียนงานภาษาไทย เขียนตามข้อกำหนดที่ผู้ใช้ระบุอย่างเคร่งครัด '
             'ตอบเฉพาะเนื้องานที่เขียน ห้ามใส่คำอธิบายหรือหมายเหตุใด ๆ เพิ่ม')
Q_SHORT = '100000 หาร 5 เท่ากับเท่าไร'
Q_WRITE = ('เขียนข้อความเชิญชวนสำหรับลูกค้าที่มีค่าไฟเดือนละ 85,000 บาท '
           'ระบบขนาด 120 kWp คืนทุนใน 5 ปี\n'
           'ข้อกำหนด: ความยาวไม่เกิน 180 ตัวอักษร · ต้องมีตัวเลข 120 และ 5 '
           '· ห้ามใช้คำโฆษณาเกินจริง · ห้ามอ้างตัวเลขอื่นที่ไม่ได้ให้มา')


def call(tag, model=MODEL, system=SYS_WRITE, q=Q_WRITE, **extra):
    payload = {'model': model,
               'messages': [{'role': 'system', 'content': system},
                            {'role': 'user', 'content': q}],
               'temperature': 0.0, 'max_tokens': 600}
    payload.update(extra)
    if system is None:
        payload['messages'] = [{'role': 'user', 'content': q}]
    body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(URL, data=body, method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', 'Bearer unused')
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            j = json.loads(r.read().decode('utf-8'))
            txt = j['choices'][0]['message']['content'].strip().replace('\n', ' ')[:70]
            print(f'{tag:34s} OK   {txt!r}', flush=True)
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode('utf-8', 'replace')[:220].replace('\n', ' ')
        except Exception:
            detail = '(อ่าน body ไม่ได้)'
        print(f'{tag:34s} HTTP {e.code}  {detail}', flush=True)
    except Exception as e:
        print(f'{tag:34s} {type(e).__name__}: {e}', flush=True)
    time.sleep(8)


print('=== แยกตัวแปรทีละอย่าง ===', flush=True)
call('1 ทำซ้ำเคสที่พัง')
call('2 เปลี่ยนเป็น system เดิม', system=SYS_STD)
call('3 system งานเขียน + คำถามสั้น', q=Q_SHORT)
call('4 ไม่ใส่ system เลย', system=None)
call('5 ลด max_tokens เหลือ 200', max_tokens=200)
call('6 temperature 0.3 แทน 0.0', temperature=0.3)

print('\n=== เช็คว่า deepseek หายล่มหรือยัง ===', flush=True)
call('deepseek + งานเขียน', model='DeepSeek-V4-Flash-0731')
call('mistral + งานเขียน', model='mistral-Nemo-Instruct-2407')
