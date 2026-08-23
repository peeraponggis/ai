#!/usr/bin/env python3
"""
ยิงชุดทดสอบไปยังผู้ให้บริการ LLM ฟรี (ไม่ต้องใช้ API key) แล้วเก็บคำตอบดิบไว้

    python3 run_providers.py --testset testset_v1.jsonl --out raw_answers.jsonl [--limit 10]

หลักการ:
  • เก็บ "คำตอบดิบ" ลงไฟล์ครั้งเดียว แล้ววิเคราะห์ซ้ำได้ไม่จำกัด (เอกสาร 02 ข้อ 6.3)
  • resume ได้ — ข้อที่มีในไฟล์ผลลัพธ์แล้วจะถูกข้าม เพราะ rate limit ทำให้รันขาดกลางคันบ่อย
  • เคารพ rate limit ของแต่ละเจ้าด้วยการเว้นระยะขั้นต่ำระหว่าง request
ใช้เฉพาะไลบรารีมาตรฐาน (urllib) — ไม่ต้อง pip install
"""
import argparse, json, os, sys, time, urllib.request, urllib.error

# min_gap = วินาทีขั้นต่ำระหว่าง request ของเจ้านั้น (จากข้อจำกัดที่ผู้ให้บริการประกาศ)
PROVIDERS = [
    {'key': 'llm7-fast',      'url': 'https://api.llm7.io/v1/chat/completions',
     'model': 'fast',    'headers': {'Authorization': 'Bearer unused'}, 'min_gap': 2.0},
    {'key': 'llm7-default',   'url': 'https://api.llm7.io/v1/chat/completions',
     'model': 'default', 'headers': {'Authorization': 'Bearer unused'}, 'min_gap': 2.0},
    {'key': 'llm7-deepseek',  'url': 'https://api.llm7.io/v1/chat/completions',
     'model': 'deepseek-r1', 'headers': {'Authorization': 'Bearer unused'}, 'min_gap': 2.0},
    {'key': 'poll-openai',    'url': 'https://text.pollinations.ai/openai',
     'model': 'openai',      'headers': {}, 'min_gap': 16.0},
    {'key': 'poll-mistral',   'url': 'https://text.pollinations.ai/openai',
     'model': 'mistral',     'headers': {}, 'min_gap': 16.0},
]

SYSTEM = ('คุณเป็นผู้ช่วยวิเคราะห์ข้อมูล ตอบเป็นภาษาไทย '
          'อธิบายสั้นที่สุดเท่าที่จำเป็น แล้วปิดท้ายด้วยบรรทัดสุดท้ายในรูปแบบ '
          '"ANSWER: <คำตอบ>" โดย <คำตอบ> ต้องเป็นค่าเดียวสั้น ๆ ไม่มีหน่วย ไม่มีเครื่องหมายคั่นหลักพัน')


def ask(provider, question, timeout=60):
    body = json.dumps({
        'model': provider['model'],
        'messages': [{'role': 'system', 'content': SYSTEM},
                     {'role': 'user', 'content': question}],
        'temperature': 0.0,
        'max_tokens': 600,
    }, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(provider['url'], data=body, method='POST')
    req.add_header('Content-Type', 'application/json')
    for k, v in provider['headers'].items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read().decode('utf-8'))
    return data['choices'][0]['message']['content']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--testset', default='testset_v1.jsonl')
    ap.add_argument('--out', default='raw_answers.jsonl')
    ap.add_argument('--limit', type=int, default=0, help='จำกัดจำนวนข้อ (ไว้ทดสอบ)')
    ap.add_argument('--retries', type=int, default=2)
    args = ap.parse_args()

    items = [json.loads(l) for l in open(args.testset, encoding='utf-8')]
    if args.limit:
        items = items[:args.limit]

    done = set()
    if os.path.exists(args.out):
        for line in open(args.out, encoding='utf-8'):
            try:
                r = json.loads(line)
                done.add((r['provider'], r['item_id']))
            except Exception:
                pass
        print(f"พบผลเดิม {len(done)} รายการ — จะข้ามข้อที่ทำแล้ว", file=sys.stderr)

    last_call = {p['key']: 0.0 for p in PROVIDERS}
    with open(args.out, 'a', encoding='utf-8') as fh:
        for prov in PROVIDERS:
            ok = err = 0
            for it in items:
                if (prov['key'], it['id']) in done:
                    continue
                gap = prov['min_gap'] - (time.time() - last_call[prov['key']])
                if gap > 0:
                    time.sleep(gap)
                content, error = None, None
                for attempt in range(args.retries + 1):
                    try:
                        content = ask(prov, it['question'])
                        break
                    except Exception as e:
                        error = f'{type(e).__name__}: {e}'
                        time.sleep(min(2 ** attempt * prov['min_gap'], 30))
                last_call[prov['key']] = time.time()
                fh.write(json.dumps({'provider': prov['key'], 'model': prov['model'],
                                     'item_id': it['id'], 'set': it['set'],
                                     'content': content, 'error': error},
                                    ensure_ascii=False) + '\n')
                fh.flush()
                ok, err = ok + (content is not None), err + (content is None)
                print(f"  {prov['key']:16} {it['id']}  {'ok' if content else 'ERR'}", file=sys.stderr)
            print(f"[{prov['key']}] สำเร็จ {ok} ผิดพลาด {err}", file=sys.stderr)


if __name__ == '__main__':
    main()
