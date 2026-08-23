#!/usr/bin/env python3
"""
ตรวจสุขภาพผู้ให้บริการทีละเจ้า — ยิงคำถามสั้น ๆ เจ้าละ 1 ครั้ง
รายงานสถานะ เวลา และข้อความ error แบบเต็ม เพื่อดูว่าเจ้าไหนมีปัญหา
    python3 probe_providers.py
"""
import json, sys, time, urllib.request, urllib.error
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from run_providers import PROVIDERS, SYSTEM

Q = 'ค่าไฟเดือนละ 100,000 บาท อัตรา 5 บาทต่อหน่วย ใช้ไฟกี่หน่วยต่อเดือน'


def list_models(url, headers):
    req = urllib.request.Request(url)
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            d = json.loads(r.read().decode('utf-8'))
        ids = [m.get('id') for m in (d.get('data') or d)] if isinstance(d, (list, dict)) else []
        print(f"   โมเดลที่มี ({len(ids)}): {', '.join(str(i) for i in ids[:25])}", flush=True)
    except Exception as e:
        print(f"   ลิสต์โมเดลไม่ได้: {type(e).__name__}: {str(e)[:100]}", flush=True)


print('== รายชื่อโมเดลของแต่ละเจ้า ==', flush=True)
from run_providers import UA
list_models('https://api.llm7.io/v1/models', {'Authorization': 'Bearer unused'})
list_models('https://text.pollinations.ai/models', {'User-Agent': UA})
print('\n== ทดสอบยิงจริงเจ้าละ 1 ครั้ง ==', flush=True)

for p in PROVIDERS:
    body = json.dumps({'model': p['model'],
                       'messages': [{'role': 'system', 'content': SYSTEM},
                                    {'role': 'user', 'content': Q}],
                       'temperature': 0.0, 'max_tokens': 200}, ensure_ascii=False).encode()
    req = urllib.request.Request(p['url'], data=body, method='POST')
    req.add_header('Content-Type', 'application/json')
    for k, v in p['headers'].items():
        req.add_header(k, v)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode('utf-8'))
        txt = data['choices'][0]['message']['content'].replace('\n', ' ')[:120]
        print(f"✅ {p['key']:16} {time.time()-t0:6.1f}s  HTTP {r.status}  → {txt}", flush=True)
    except urllib.error.HTTPError as e:
        detail = e.read().decode('utf-8', 'replace')[:200].replace('\n', ' ')
        print(f"❌ {p['key']:16} {time.time()-t0:6.1f}s  HTTP {e.code}  {detail}", flush=True)
    except Exception as e:
        print(f"❌ {p['key']:16} {time.time()-t0:6.1f}s  {type(e).__name__}: {str(e)[:160]}", flush=True)
