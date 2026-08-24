#!/usr/bin/env python3
"""
จำลองว่า "สัดส่วนโมเดลที่เห็นตรงกัน" บอกความน่าจะเป็นที่คำตอบถูกได้แค่ไหน
เมื่อความผิดพลาดของโมเดลมีความสัมพันธ์กัน (rho)

ใช้เฉพาะไลบรารีมาตรฐานของ Python — รันได้ทุกเครื่อง ไม่ต้องติดตั้งอะไร
    python3 sim_agreement.py

โมเดลจำลอง: ข้อสอบแต่ละข้อมี "ความยาก" ร่วมกัน (latent difficulty) ซึ่งทำให้
โมเดลทุกตัวพลาดพร้อมกันในข้อยาก — นี่คือกลไกที่ทำให้เกิด rho ในโลกจริง
(โมเดลเทรนจากข้อมูลคล้ายกัน จึงไม่รู้เรื่องเดียวกัน)
"""
import random, math
from math import comb

SEED = 20260823
N_ITEMS = 20000          # จำนวนข้อในการจำลอง
N_MODELS = 5
P_TARGET = 0.70          # ความแม่นเฉลี่ยของโมเดลเดี่ยว
N_WRONG_OPTIONS = 3      # เมื่อตอบผิด เลือกจากคำตอบผิด 3 แบบ (จำลองการ "ผิดเหมือนกัน")


def logistic(x):
    return 1.0 / (1.0 + math.exp(-x))


def _solve_mu(p_target, sd, seed, n=40000):
    """หา mu ที่ทำให้ค่าเฉลี่ยของ logistic(mu + noise) เท่ากับ p_target พอดี"""
    rnd = random.Random(seed + 999)
    noise = [rnd.gauss(0, sd) for _ in range(n)]
    lo, hi = -6.0, 6.0
    for _ in range(60):
        mid = (lo + hi) / 2
        got = sum(logistic(mid + z) for z in noise) / n
        if got < p_target:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def simulate(rho, n_models=N_MODELS, p=P_TARGET, n_items=N_ITEMS, seed=SEED):
    """
    rho ควบคุมผ่านสัดส่วนความแปรปรวนที่มาจาก 'ความยากของข้อ' (ร่วมกัน)
    เทียบกับความแปรปรวนเฉพาะตัวของแต่ละโมเดล — โครงสร้างเดียวกับ random-effects model
    """
    rnd = random.Random(seed)
    # แปลง p เป็น logit แล้วกระจายความแปรปรวนระหว่าง shared / individual
    total_sd = 1.6
    shared_sd = total_sd * math.sqrt(rho)
    indiv_sd = total_sd * math.sqrt(max(0.0, 1 - rho))
    # ถ้าใช้ mu = logit(p) ตรง ๆ ความแม่นจริงจะต่ำกว่า p เพราะ E[logistic(mu+noise)] != logistic(mu)
    # (ความไม่เท่าเทียมของ Jensen — logistic เว้าลงเมื่อ mu > 0) จึงต้องแก้ mu ด้วย bisection
    mu = _solve_mu(p, total_sd, seed)

    rows = []
    for _ in range(n_items):
        difficulty = rnd.gauss(0, shared_sd)          # ร่วมกันทุกโมเดลในข้อนี้
        answers = []
        for _m in range(n_models):
            skill = rnd.gauss(0, indiv_sd)            # เฉพาะตัวโมเดล
            correct = rnd.random() < logistic(mu + difficulty + skill)
            if correct:
                answers.append(0)                     # 0 = คำตอบที่ถูก
            else:
                # ข้อยากมักดึงให้ผิดไปทาง "คำตอบลวง" ตัวเดียวกัน
                bias = 1 if difficulty > 0 else rnd.randint(1, N_WRONG_OPTIONS)
                answers.append(bias if rnd.random() < 0.6 else rnd.randint(1, N_WRONG_OPTIONS))
        rows.append(answers)
    return rows


def majority(answers):
    counts = {}
    for a in answers:
        counts[a] = counts.get(a, 0) + 1
    top = max(counts.values())
    winners = sorted([a for a, c in counts.items() if c == top])
    return winners[0], top / len(answers)          # (คำตอบที่ชนะ, สัดส่วนเห็นพ้อง)


def measure_rho(rows):
    """สหสัมพันธ์เฉลี่ยของ 'ความผิดพลาด' ระหว่างคู่โมเดล (phi coefficient)"""
    n_models = len(rows[0])
    vals = []
    for i in range(n_models):
        for j in range(i + 1, n_models):
            a = [1 if r[i] != 0 else 0 for r in rows]
            b = [1 if r[j] != 0 else 0 for r in rows]
            n = len(a)
            ma, mb = sum(a) / n, sum(b) / n
            cov = sum((x - ma) * (y - mb) for x, y in zip(a, b)) / n
            sa = math.sqrt(sum((x - ma) ** 2 for x in a) / n)
            sb = math.sqrt(sum((y - mb) ** 2 for y in b) / n)
            if sa > 0 and sb > 0:
                vals.append(cov / (sa * sb))
    return sum(vals) / len(vals) if vals else 0.0


def pava(xs, ys):
    """Isotonic regression (Pool Adjacent Violators) — ไม่ต้องพึ่ง sklearn"""
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    y = [ys[i] for i in order]
    w = [1.0] * len(y)
    i = 0
    while i < len(y) - 1:
        if y[i] > y[i + 1]:
            tw = w[i] + w[i + 1]
            ty = (y[i] * w[i] + y[i + 1] * w[i + 1]) / tw
            y[i:i + 2] = [ty]
            w[i:i + 2] = [tw]
            i = max(i - 1, 0)
        else:
            i += 1
    out, k = [], 0
    for val, weight in zip(y, w):
        out.extend([val] * int(round(weight)))
    return [xs[order[i]] for i in range(len(order))], out


def brier(preds, actuals):
    return sum((p - a) ** 2 for p, a in zip(preds, actuals)) / len(preds)


def ece(preds, actuals, bins=10):
    total, n = 0.0, len(preds)
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        idx = [i for i, p in enumerate(preds) if (lo < p <= hi or (b == 0 and p == 0))]
        if not idx:
            continue
        conf = sum(preds[i] for i in idx) / len(idx)
        acc = sum(actuals[i] for i in idx) / len(idx)
        total += len(idx) / n * abs(conf - acc)
    return total


def theoretical_pmaj(n, p):
    return sum(comb(n, k) * p ** k * (1 - p) ** (n - k) for k in range(n // 2 + 1, n + 1))


if __name__ == '__main__':
    print(f"จำลอง {N_ITEMS:,} ข้อ · {N_MODELS} โมเดล · ความแม่นเดี่ยวเป้าหมาย {P_TARGET:.0%}\n")
    print("| rho ที่ตั้ง | rho ที่วัดได้ | ความแม่นเดี่ยว | เสียงข้างมาก | ทฤษฎี (อิสระ) | ส่วนต่าง |")
    print("|---|---|---|---|---|---|")
    calib_data = {}
    for rho in (0.0, 0.2, 0.4, 0.6, 0.8):
        rows = simulate(rho)
        solo = sum(1 for r in rows for a in r if a == 0) / (len(rows) * N_MODELS)
        maj = [majority(r) for r in rows]
        maj_acc = sum(1 for m in maj if m[0] == 0) / len(maj)
        theo = theoretical_pmaj(N_MODELS, solo)
        print(f"| {rho:.1f} | {measure_rho(rows):.3f} | {solo:.1%} | **{maj_acc:.1%}** | {theo:.1%} | {maj_acc - theo:+.1%} |")
        calib_data[rho] = (maj, [1 if m[0] == 0 else 0 for m in maj])

    print("\n### สัดส่วนเห็นพ้อง → ความแม่นจริง (ไม่ใช่ค่าเดียวกัน)\n")
    print("| เห็นพ้อง | rho=0.0 | rho=0.4 | rho=0.8 |")
    print("|---|---|---|---|")
    for frac in (3 / 5, 4 / 5, 5 / 5):
        cells = []
        for rho in (0.0, 0.4, 0.8):
            maj, correct = calib_data[rho]
            idx = [i for i, m in enumerate(maj) if abs(m[1] - frac) < 1e-9]
            cells.append(f"{sum(correct[i] for i in idx) / len(idx):.1%} (n={len(idx):,})" if idx else "—")
        print(f"| {int(frac * 5)}/5 = {frac:.0%} | " + " | ".join(cells) + " |")

    print("\n### ใช้สัดส่วนเห็นพ้องเป็นความน่าจะเป็นดิบ vs หลัง calibrate\n")
    print("| rho | Brier (ดิบ) | ECE (ดิบ) | Brier (isotonic) | ECE (isotonic) |")
    print("|---|---|---|---|---|")
    for rho in (0.0, 0.4, 0.8):
        maj, correct = calib_data[rho]
        raw = [m[1] for m in maj]
        half = len(raw) // 2
        xs_fit, ys_fit = pava(raw[:half], correct[:half])          # fit ครึ่งแรก
        lookup = {}
        for x, y in zip(xs_fit, ys_fit):
            lookup.setdefault(round(x, 6), []).append(y)
        lookup = {k: sum(v) / len(v) for k, v in lookup.items()}
        cal = [lookup.get(round(x, 6), x) for x in raw[half:]]      # ประเมินครึ่งหลัง
        act = correct[half:]
        rawv = raw[half:]
        print(f"| {rho:.1f} | {brier(rawv, act):.4f} | {ece(rawv, act):.4f} | "
              f"**{brier(cal, act):.4f}** | **{ece(cal, act):.4f}** |")
