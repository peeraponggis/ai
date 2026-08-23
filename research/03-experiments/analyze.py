#!/usr/bin/env python3
"""
วิเคราะห์คำตอบดิบ → p (พร้อม Wilson CI), เมทริกซ์ phi, double-fault,
ความแม่นของเสียงข้างมาก, ตารางความเห็นพ้อง→ความแม่น และผลการ calibrate

    python3 analyze.py --testset testset_v1.jsonl --raw raw_answers.jsonl --out RESULTS.md
"""
import argparse, json, math, re, sys
from collections import defaultdict

NUM_TOL = 0.01          # ยอมคลาดเคลื่อน 1% สำหรับคำตอบเชิงตัวเลข


# ---------- การตรวจคำตอบ ----------
def extract_answer(text):
    """ดึงค่าหลัง ANSWER: ถ้าไม่มีให้ใช้บรรทัดสุดท้ายที่ไม่ว่าง"""
    if not text:
        return None
    m = re.findall(r'ANSWER\s*[:：]\s*(.+)', text, re.IGNORECASE)
    if m:
        return m[-1].strip().strip('*`" ')
    lines = [l.strip() for l in text.strip().splitlines() if l.strip()]
    return lines[-1].strip('*`" ') if lines else None


def to_number(s):
    if s is None:
        return None
    s = str(s).replace(',', '').replace('บาท', '').replace('%', '').strip()
    m = re.search(r'-?\d+(?:\.\d+)?', s)
    return float(m.group(0)) if m else None


def norm_text(s):
    return re.sub(r'\s+', ' ', str(s)).strip().lower() if s is not None else None


def is_correct(item, raw):
    got = extract_answer(raw)
    if got is None:
        return False
    if item['type'] == 'number':
        g, a = to_number(got), float(item['answer'])
        if g is None:
            return False
        return abs(g - a) <= max(abs(a) * NUM_TOL, 1e-9)
    if item['type'] == 'label':
        return norm_text(item['answer']) in norm_text(got)
    return norm_text(got) == norm_text(item['answer'])


def answer_key(item, raw):
    """คีย์สำหรับจัดกลุ่มคำตอบก่อนโหวต — ตัวเลขปัดตามความละเอียด, ข้อความ normalize"""
    got = extract_answer(raw)
    if got is None:
        return None
    if item['type'] == 'number':
        n = to_number(got)
        return None if n is None else round(n, 4)
    return norm_text(got)


# ---------- สถิติ ----------
def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return p, max(0.0, c - h), min(1.0, c + h)


def phi(a, b):
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b)) / n
    sa = math.sqrt(sum((x - ma) ** 2 for x in a) / n)
    sb = math.sqrt(sum((y - mb) ** 2 for y in b) / n)
    return cov / (sa * sb) if sa > 0 and sb > 0 else 0.0


def brier(p, y):
    return sum((a - b) ** 2 for a, b in zip(p, y)) / len(p) if p else float('nan')


def ece(p, y, bins=5):
    n, tot = len(p), 0.0
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        idx = [i for i, v in enumerate(p) if (lo < v <= hi or (b == 0 and v == 0))]
        if idx:
            tot += len(idx) / n * abs(sum(p[i] for i in idx) / len(idx)
                                      - sum(y[i] for i in idx) / len(idx))
    return tot


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--testset', default='testset_v1.jsonl')
    ap.add_argument('--raw', default='raw_answers.jsonl')
    ap.add_argument('--out', default='RESULTS.md')
    args = ap.parse_args()

    items = {j['id']: j for j in (json.loads(l) for l in open(args.testset, encoding='utf-8'))}
    raw = [json.loads(l) for l in open(args.raw, encoding='utf-8')]

    correct = defaultdict(dict)   # provider -> item_id -> 0/1
    keyed = defaultdict(dict)     # provider -> item_id -> answer key
    errors = defaultdict(int)
    for r in raw:
        it = items.get(r['item_id'])
        if not it:
            continue
        if r.get('error') and not r.get('content'):
            # ไม่มีคำตอบ = ไม่ได้ตอบผิด ต้องไม่นับเป็นข้อที่ตอบพลาด
            # ไม่งั้นโมเดลที่ถูก rate limit จนไม่ได้ตอบเลย จะถูกรายงานว่าแม่น 0%
            errors[r['provider']] += 1
            continue
        correct[r['provider']][r['item_id']] = 1 if is_correct(it, r.get('content')) else 0
        keyed[r['provider']][r['item_id']] = answer_key(it, r.get('content'))

    provs = sorted(set(correct) | set(errors))
    err_kinds = defaultdict(lambda: defaultdict(int))
    for r in raw:
        if r.get('error') and not r.get('content'):
            # ย่อข้อความ error ให้เหลือชนิด เพื่อให้เห็นว่าล้มเพราะอะไรจริง ๆ
            m = re.search(r'HTTP(?:\s*Error)?\s*(\d{3})', str(r['error']))
            kind = f'HTTP {m.group(1)}' if m else str(r['error']).split(':')[0][:40]
            err_kinds[r['provider']][kind] += 1
    # นับจากข้อที่ยิงจริง ไม่ใช่ขนาดชุดทดสอบเต็ม เพราะ --limit ยิงไม่ครบชุด
    # ไม่งั้นรอบที่ยิง 60 จาก 100 จะรายงานว่าตอบ 53% ทั้งที่ตอบ 53 จาก 60 = 88%
    attempted = {r['item_id'] for r in raw if r.get('item_id') in items}
    TOTAL = len(attempted) or len(items)
    MIN_ANSWERED = max(20, TOTAL // 3)   # ตอบน้อยกว่านี้ ประมาณค่า p ไม่ได้
    L = []
    L.append('# ผลการวัดจริง — โมเดลฟรีบนชุดทดสอบภาษาไทย\n')
    L.append(f'ยิงจริง {TOTAL} ข้อ (จากชุด {len(items)} ข้อ) · ผู้ให้บริการ {len(provs)} ราย\n')
    L.append('> p วัดจาก**ข้อที่โมเดลตอบจริง**เท่านั้น ข้อที่เรียกไม่สำเร็จถูกตัดออกจากตัวหาร\n'
             '> เพราะ "ไม่ได้ตอบ" กับ "ตอบผิด" เป็นคนละเรื่อง — อัตราการตอบรายงานแยกอีกคอลัมน์\n')

    L.append('## 1. ความแม่นรายโมเดล (p) พร้อมช่วงเชื่อมั่น 95% (Wilson)\n')
    L.append('| โมเดล | ตอบจริง | อัตราตอบ | ถูก | p̂ | ช่วง 95% | เรียก error | สถานะ |')
    L.append('|---|---|---|---|---|---|---|---|')
    keep = []
    for p_ in provs:
        ids = sorted(correct.get(p_, {}))
        n = len(ids)
        k = sum(correct[p_][i] for i in ids)
        est, lo, hi = wilson(k, n) if n else (0.0, 0.0, 0.0)
        if n < MIN_ANSWERED:
            status = f'❌ ตอบน้อยเกินไป (<{MIN_ANSWERED})'
        elif lo > 0.5:
            status = '✅ นำเข้าโหวต'
            keep.append(p_)
        else:
            status = '❌ ตัดออก (p อาจต่ำกว่า 0.5)'
        L.append(f'| `{p_}` | {n} | {n / TOTAL:.0%} | {k} | '
                 f'{est:.1%} | {lo:.1%} – {hi:.1%} | {errors[p_]} | {status} |')

    # เทียบ φ และการโหวตได้เฉพาะข้อที่โมเดลที่ผ่านเกณฑ์ตอบครบทุกตัว
    common = sorted(set.intersection(*[set(correct[p]) for p in keep])) if keep else []
    L.append(f'\nข้อที่โมเดลผ่านเกณฑ์ตอบครบทุกตัว: {len(common)} ข้อ\n')

    if any(err_kinds.values()):
        L.append('### สาเหตุที่เรียกไม่สำเร็จ\n')
        L.append('| โมเดล | สาเหตุ |')
        L.append('|---|---|')
        for p_ in provs:
            if not err_kinds[p_]:
                continue
            top = sorted(err_kinds[p_].items(), key=lambda kv: -kv[1])[:4]
            L.append(f'| `{p_}` | ' + ' · '.join(f'{k} ×{v}' for k, v in top) + ' |')
        L.append('')

    L.append('\n### แยกตามหมวด\n')
    sets = sorted({items[i]['set'] for i in attempted})
    L.append('| โมเดล | ' + ' | '.join(s + ' (n)' for s in sets) + ' |')
    L.append('|---' * (len(sets) + 1) + '|')
    for p_ in provs:
        cells = []
        for s in sets:
            ids = [i for i in correct.get(p_, {}) if items[i]['set'] == s]
            cells.append(f'{sum(correct[p_][i] for i in ids) / len(ids):.0%} ({len(ids)})'
                         if ids else '— (0)')
        L.append(f'| `{p_}` | ' + ' | '.join(cells) + ' |')

    L.append(f'\n**โมเดลที่ผ่านเกณฑ์และนำเข้าโหวต: {len(keep)} ตัว** — '
             f'{", ".join("`" + k + "`" for k in keep) if keep else "ไม่มี"}\n')

    if len(keep) >= 3:
        L.append('## 2. สหสัมพันธ์ของความผิดพลาด (φ) และ double-fault\n')
        L.append('| คู่โมเดล | φ | ผิดทั้งคู่ |')
        L.append('|---|---|---|')
        phis = []
        for a in range(len(keep)):
            for b in range(a + 1, len(keep)):
                ea = [1 - correct[keep[a]][i] for i in common]
                eb = [1 - correct[keep[b]][i] for i in common]
                v = phi(ea, eb)
                phis.append(v)
                df = sum(1 for x, y in zip(ea, eb) if x and y) / len(ea)
                L.append(f'| `{keep[a]}` ↔ `{keep[b]}` | {v:+.3f} | {df:.1%} |')
        L.append(f'\n**φ เฉลี่ย = {sum(phis) / len(phis):+.3f}** '
                 f'(ยิ่งต่ำยิ่งดี — ดูผลกระทบในเอกสาร 02 ข้อ 5)\n')

        L.append('## 3. เสียงข้างมาก vs โมเดลเดี่ยวที่ดีที่สุด\n')
        agree, hit = [], []
        for i in common:
            votes = [keyed[p_][i] for p_ in keep if keyed[p_][i] is not None]
            if not votes:
                agree.append(0.0); hit.append(0); continue
            counts = defaultdict(int)
            for v in votes:
                counts[v] += 1
            win, c = max(counts.items(), key=lambda kv: kv[1])
            agree.append(c / len(keep))
            # ถูกไหม: เทียบ key ที่ชนะกับเฉลยด้วยกติกาเดียวกับ is_correct
            ok = any(correct[p_][i] and keyed[p_][i] == win for p_ in keep)
            hit.append(1 if ok else 0)
        maj = sum(hit) / len(hit)
        best = max(sum(correct[p_][i] for i in common) / len(common) for p_ in keep)
        e, lo, hi = wilson(sum(hit), len(hit))
        L.append(f'- โมเดลเดี่ยวที่ดีที่สุด: **{best:.1%}**')
        L.append(f'- เสียงข้างมาก ({len(keep)} โมเดล): **{maj:.1%}** (95% CI {lo:.1%} – {hi:.1%})')
        L.append(f'- ส่วนต่าง: **{maj - best:+.1%}** → '
                 f'{"✅ ensemble ชนะ" if lo > best else "❌ ยังพิสูจน์ไม่ได้ว่าชนะ (CI คลุมค่าเดี่ยว)"}\n')

        L.append('## 4. ความเห็นพ้อง → ความแม่นจริง\n')
        L.append('| สัดส่วนเห็นพ้อง | จำนวนข้อ | ถูกจริง |')
        L.append('|---|---|---|')
        buckets = defaultdict(list)
        for a, h in zip(agree, hit):
            buckets[round(a, 3)].append(h)
        for a in sorted(buckets):
            v = buckets[a]
            L.append(f'| {a:.0%} | {len(v)} | {sum(v) / len(v):.1%} |')
        L.append(f'\n- ใช้สัดส่วนดิบเป็นความน่าจะเป็น: Brier **{brier(agree, hit):.4f}** · '
                 f'ECE **{ece(agree, hit):.4f}**')
        mapped = [sum(buckets[round(a, 3)]) / len(buckets[round(a, 3)]) for a in agree]
        L.append(f'- หลังแมปด้วยตารางด้านบน (in-sample ยังไม่ได้แยก fit/test): '
                 f'Brier **{brier(mapped, hit):.4f}** · ECE **{ece(mapped, hit):.4f}**')
        L.append('\n> ⚠️ ตัวเลข calibrate ข้างบนเป็น in-sample ต้องแยก fit/test '
                 'เมื่อมีข้อมูลมากพอ (ดูเอกสาร 02 ข้อ 4)')
    else:
        L.append('\n> โมเดลผ่านเกณฑ์น้อยกว่า 3 ตัว — ยังวิเคราะห์การโหวตไม่ได้\n')

    out = '\n'.join(L) + '\n'
    open(args.out, 'w', encoding='utf-8').write(out)
    print(out)


if __name__ == '__main__':
    main()
