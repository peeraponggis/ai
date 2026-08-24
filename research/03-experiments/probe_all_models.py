#!/usr/bin/env python3
"""
ดึงรายชื่อโมเดลทั้งหมดจาก /v1/models แล้วยิงทีละตัวเพื่อดูว่า
"ตัวไหนใช้ได้จริงโดยไม่ต้องมี API key" — คำถามข้อเดียวสั้น ๆ

ผลลัพธ์ที่ต้องการคือรายชื่อโมเดลที่ใช้ได้ฟรีจริง เพื่อเอาไปตั้งค่าการวัด p และ phi
"""
import json, time, urllib.request, urllib.error

BASE = 'https://api.llm7.io/v1'
HDR = {'Authorization': 'Bearer unused', 'Content-Type': 'application/json'}
Q = 'ค่าไฟเดือนละ 100,000 บาท อัตรา 5 บาทต่อหน่วย ใช้ไฟกี่หน่วยต่อเดือน'
SYS = 'ตอบสั้นที่สุด ปิดท้ายด้วยบรรทัด "ANSWER: <ตัวเลข>" เท่านั้น'


def get_models():
    req = urllib.request.Request(f'{BASE}/models')
    for k, v in HDR.items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.loads(r.read().decode('utf-8'))
    raw = d.get('data', d) if isinstance(d, dict) else d
    return [(m.get('id') or m.get('name')) if isinstance(m, dict) else m for m in raw]


def try_model(mid, timeout=45):
    body = json.dumps({'model': mid,
                       'messages': [{'role': 'system', 'content': SYS},
                                    {'role': 'user', 'content': Q}],
                       'temperature': 0.0, 'max_tokens': 150}, ensure_ascii=False).encode()
    req = urllib.request.Request(f'{BASE}/chat/completions', data=body, method='POST')
    for k, v in HDR.items():
        req.add_header(k, v)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode('utf-8'))
        txt = d['choices'][0]['message']['content'].replace('\n', ' ').strip()
        return 'OK', time.time() - t0, txt[:90]
    except urllib.error.HTTPError as e:
        try:
            code = json.loads(e.read().decode('utf-8'))['error'].get('code', str(e.code))
        except Exception:
            code = str(e.code)
        return f'HTTP {e.code}', time.time() - t0, code
    except Exception as e:
        return 'ERR', time.time() - t0, f'{type(e).__name__}'


if __name__ == '__main__':
    models = get_models()
    print(f'พบโมเดลในลิสต์ {len(models)} ตัว — ทดสอบทีละตัว\n', flush=True)
    ok = []
    for i, m in enumerate(models, 1):
        status, dt, info = try_model(m)
        if status == 'HTTP 429':                     # โดน rate limit → พักแล้วลองใหม่ 1 ครั้ง
            time.sleep(3)
            status, dt, info = try_model(m)
        mark = '✅' if status == 'OK' else '❌'
        print(f'{mark} [{i:2}/{len(models)}] {m:38} {status:9} {dt:5.1f}s  {info}', flush=True)
        if status == 'OK':
            ok.append(m)
        time.sleep(1.2)
    print(f'\n=== ใช้ได้ฟรีจริง {len(ok)} ตัว จาก {len(models)} ===', flush=True)
    for m in ok:
        print(f'  {m}', flush=True)
