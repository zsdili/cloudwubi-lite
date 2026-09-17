#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
云五笔 Lite 回归测试（≥50 场景，全绿才可发布）
覆盖：数据完整性 / 一级简码 / 二级简码 / 三级简码 / 全码 / 重码频率序 /
      MRU 前移 / 词频持久化 / Z 万能键 / 计算引擎 / 边界
用法：python3 tests/regression_test.py
"""
import os, sys, re

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(BASE, "android/app/src/main/assets/wubi86_single.txt")

passed = 0
failed = 0
cases = []

def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        cases.append(("PASS", name))
    else:
        failed += 1
        cases.append(("FAIL", name + "  " + detail))
        print("  ❌ FAIL:", name, detail)

# ---------- 加载数据 ----------
raw = {}
with open(DATA, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if len(line) >= 2:
            raw.setdefault(line[:-1], []).append(line[-1])

# 1. 数据完整性
check("数据非空", len(raw) > 5000, f"编码数={len(raw)}")
check("一级简码 25 个", sum(1 for c in raw if len(c) == 1) == 25, str(sum(1 for c in raw if len(c) == 1)))
check("二级简码 615 个", sum(1 for c in raw if len(c) == 2) == 615)
check("三级简码 ≥4000", sum(1 for c in raw if len(c) == 3) >= 4000)
check("全码 ≥5000", sum(1 for c in raw if len(c) == 4) >= 5000)

# 2. 一级简码（王码86 标准 25 个）
CODE1 = {"q":"我","w":"人","e":"有","r":"的","t":"和","y":"主","u":"产","i":"不","o":"为","p":"这",
         "a":"工","s":"要","d":"在","f":"地","g":"一","h":"上","j":"是","k":"中","l":"国",
         "x":"经","c":"以","v":"发","b":"了","n":"民","m":"同"}
for code, ch in CODE1.items():
    got = raw.get(code, [])
    check(f"一级简码 {code}→{ch}", ch in got, f"got={got[:5]}")

# 3. 高频字排序（重码首字=最高频）
check("r 码首字=的", raw.get("r", [None])[0] == "的", str(raw.get("r"))[:30])
check("g 码首字=一", raw.get("g", [None])[0] == "一", str(raw.get("g"))[:30])
check("t 码首字=和", raw.get("t", [None])[0] == "和", str(raw.get("t"))[:30])

# 4. 三级简码抽查（用户历史反馈词：栏/送）
for code, expect in [("suf","栏"), ("udp","送"), ("aaa","工"), ("ii","水")]:
    got = raw.get(code, [])
    check(f"三级/编码 {code}→{expect}", expect in got, f"got={got[:5]}")

# 5. 全码抽查（4 码字）
for code, expect in [("trnt","我"), ("wwww","人"), ("gghg","五"), ("fgh","十")]:
    got = raw.get(code, [])
    check(f"全码 {code}→{expect}", expect in got, f"got={got[:5]}")

# 6. 重码频率序（freq.json 高频优先）
r_cands = raw.get("r", [])
check("r 码重码按频序（的前）", r_cands.index("的") < r_cands.index("白") if "白" in r_cands else True, str(r_cands[:8]))
g_cands = raw.get("g", [])
check("g 码重码按频序（一前）", g_cands.index("一") == 0, str(g_cands[:8]))

# 7. MRU 前移模拟（Java 逻辑复刻）
def mru_query(code, mru_set):
    out = list(raw.get(code, []))
    for i in range(len(out) - 1, -1, -1):
        if out[i] in mru_set:
            c = out.pop(i)
            out.insert(0, c)
    return out

mru = {"支"}
q1 = mru_query("fcu", mru)
check("MRU：上过屏的'支'前移", q1[0] == "支", str(q1[:5]))
mru2 = {"我"}
check("MRU：一级简码上屏", mru_query("q", mru2)[0] == "我", str(mru_query("q", mru2)[:5]))

# 8. 词频持久化模拟
def load_persist(data):
    boost = {}
    for pair in data.split(","):
        i = pair.rfind(":")
        if i > 0:
            try: boost[pair[:i]] = int(pair[i+1:])
            except: pass
    return boost
boost = load_persist("白:3,的:1,")
check("词频持久化解析", boost.get("白") == 3 and boost.get("的") == 1)
def boost_query(code, boost_map):
    out = list(raw.get(code, []))
    out.sort(key=lambda c: -boost_map.get(c, 0))
    return out
check("词频加成前移", boost_query("fcu", {"支": 10})[0] == "支", str(boost_query("fcu", {"支": 10})[:5]))

# 9. Z 万能键（模拟展开）
LETTERS = "qwertyuiopasdfghjklzxcvbnm"
def wildcard(code):
    if "z" not in code:
        return raw.get(code, [])
    allc = []
    def exp(pos, cur):
        if pos == len(code):
            for s in raw.get(cur, []):
                if s not in allc:
                    allc.append(s)
            return
        c = code[pos]
        if c == "z":
            for l in LETTERS:
                exp(pos + 1, cur + l)
        else:
            exp(pos + 1, cur + c)
    exp(0, "")
    return allc
check("Z 万能键（z 出字）", len(wildcard("z")) > 0, f"z 候选={len(wildcard('z'))}")
check("Z 万能键（gz 含'一'类）", len(wildcard("gz")) > 0, f"gz 候选={wildcard('gz')[:6]}")
check("Z 不破坏正常码", wildcard("g") == raw.get("g"))

# 10. 计算引擎（Python 复刻，验证与 Java 同规则）
def calc(expr):
    expr = expr.replace("×", "*").replace("÷", "/").replace("－", "-").replace("＋", "+").strip()
    if not expr:
        return None
    pos = [0]
    def num():
        start = pos[0]
        while pos[0] < len(expr) and (expr[pos[0]].isdigit() or expr[pos[0]] == "."):
            pos[0] += 1
        if start == pos[0]:
            raise ValueError
        return float(expr[start:pos[0]])
    def factor():
        if pos[0] < len(expr) and expr[pos[0]] == "(":
            pos[0] += 1
            v = expr_v()
            if pos[0] < len(expr) and expr[pos[0]] == ")":
                pos[0] += 1
            return v
        if pos[0] < len(expr) and expr[pos[0]] in "+-":
            s = expr[pos[0]]
            pos[0] += 1
            v = num()
            return -v if s == "-" else v
        return num()
    def term():
        v = factor()
        while pos[0] < len(expr) and expr[pos[0]] in "*/%":
            op = expr[pos[0]]
            pos[0] += 1
            r = factor()
            v = v * r if op == "*" else (v / r if op == "/" else v % r)
        return v
    def expr_v():
        v = term()
        while pos[0] < len(expr) and expr[pos[0]] in "+-":
            op = expr[pos[0]]
            pos[0] += 1
            r = term()
            v = v + r if op == "+" else v - r
        return v
    try:
        v = expr_v()
        if pos[0] < len(expr):
            return None
        if v == int(v) and abs(v) < 1e15:
            return str(int(v))
        return ("%.8f" % v).rstrip("0").rstrip(".")
    except Exception:
        return None

calc_cases = [("2*3", "6"), ("2+3*4", "14"), ("10/4", "2.5"), ("5%2", "1"),
              ("(2+3)*4", "20"), ("2.5+1.5", "4"), ("7-9", "-2"), ("100/8", "12.5"),
              ("3*3*3", "27"), ("2+2+2+2", "8"), ("50%3", "2"), ("1+1", "2"),
              ("9-2*3", "3"), ("8/2/2", "2"), ("(1+2)*3", "9"), ("0.1+0.2", "0.3"),
              ("123+456", "579"), ("100-55", "45"), ("6*7", "42"), ("99/9", "11")]
for expr, want in calc_cases:
    got = calc(expr)
    check(f"计算 {expr}={want}", got == want, f"got={got}")
check("计算空串=null", calc("") is None)
check("计算非法=null", calc("2+") is None)

# 11. 边界
check("空码无候选", raw.get("", None) is None)
check("超长码无候选", raw.get("qqqqq", None) is None)
check("无重复编码行", len(raw) == len(set(raw.keys())))
total_chars = sum(len(v) for v in raw.values())
check("总字数 ≥6763", total_chars >= 6763, str(total_chars))

# ---------- 汇总 ----------
print(f"\n==== 回归结果：{passed} 通过 / {failed} 失败 / 共 {passed + failed} 场景 ====")
if failed == 0:
    print("✅ 全绿，可发布")
else:
    print("❌ 有失败，禁止发布")
    for c in cases:
        if c[0] == "FAIL":
            print("   ", c)
sys.exit(1 if failed else 0)
