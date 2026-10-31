# Independent per-bar (stateful) re-implementation of the Pine formulas, used ONLY to cross-check the Kotlin port.
import random, math, csv
from datetime import datetime, timedelta, timezone
random.seed(7)
IST = timezone(timedelta(hours=5, minutes=30))
bars = []
price = 1000.0
day = datetime(2026, 9, 1, 9, 15, tzinfo=IST)
nd = 0
while nd < 12:
    if day.weekday() < 5:
        t = day
        for k in range(75):
            o = price
            c = o * (1 + random.gauss(0, 0.002))
            h = max(o, c) * (1 + abs(random.gauss(0, 0.001)))
            l = min(o, c) * (1 - abs(random.gauss(0, 0.001)))
            v = random.randint(1000, 90000)
            bars.append((int(t.timestamp() * 1000), o, h, l, c, v))
            price = c
            t += timedelta(minutes=5)
        nd += 1
    day += timedelta(days=1)
    day = day.replace(hour=9, minute=15)
with open('bars.csv', 'w') as f:
    w = csv.writer(f)
    for b in bars: w.writerow(b)
T = [b[0] for b in bars]; O = [b[1] for b in bars]; H = [b[2] for b in bars]
L = [b[3] for b in bars]; C = [b[4] for b in bars]; V = [b[5] for b in bars]
N = len(C)
nan = float('nan')

def sma(x, n):
    o = [nan]*len(x)
    for i in range(n-1, len(x)):
        w = x[i-n+1:i+1]
        if not any(math.isnan(v) for v in w): o[i] = sum(w)/n
    return o
def ema(x, n):
    a = 2/(n+1); o = []; p = nan
    for v in x:
        if math.isnan(v): r = nan
        elif math.isnan(p): r = v
        else: r = a*v+(1-a)*p
        o.append(r); p = r
    return o
def rma(x, n):
    a = 1/n; s = sma(x, n); o = []; p = nan
    for i, v in enumerate(x):
        r = s[i] if math.isnan(p) else a*v+(1-a)*p
        o.append(r); p = r
    return o
def rsi(x, n):
    g = [nan]+[max(x[i]-x[i-1], 0) for i in range(1, len(x))]
    l = [nan]+[-min(x[i]-x[i-1], 0) for i in range(1, len(x))]
    u = rma(g, n); d = rma(l, n); o = []
    for a, b in zip(u, d):
        if math.isnan(a) or math.isnan(b): o.append(nan)
        elif b == 0: o.append(100.0)
        elif a == 0: o.append(0.0)
        else: o.append(100-100/(1+a/b))
    return o
def trf(handle):
    o = []
    for i in range(N):
        if i == 0: o.append(H[0]-L[0] if handle else nan)
        else: o.append(max(H[i]-L[i], abs(H[i]-C[i-1]), abs(L[i]-C[i-1])))
    return o
def cross_state(a, b, val):
    d = 0; age = 0; at = nan; D = []; A = []; X = []
    for i in range(len(a)):
        up = i > 0 and a[i] > b[i] and a[i-1] <= b[i-1]
        dn = i > 0 and a[i] < b[i] and a[i-1] >= b[i-1]
        if up: d, age, at = 1, 0, val[i]
        elif dn: d, age, at = -1, 0, val[i]
        elif d != 0: age += 1
        D.append(d); A.append(age); X.append(at)
    return D, A, X
out = {}
# RSI20 / RSI100 and MRSI cross
r20 = rsi(C, 20); r100 = rsi(C, 100)
out['rsi20'] = r20; out['rsi100'] = r100
d, a, x = cross_state(r20, r100, r20); out['mrsi_dir'] = d; out['mrsi_age'] = a
m14 = sma(r20, 14); out['sma14_rsi20'] = m14
d, a, x = cross_state(r20, m14, r20); out['rsisma_dir'] = d; out['rsisma_age'] = a
# GMMA guppy
fast = [0.0]*N; slow = [0.0]*N
for n in (3,5,8,10,12,15):
    e = ema(C, n); fast = [p+q for p, q in zip(fast, e)]
for n in (30,35,40,45,50,60):
    e = ema(C, n); slow = [p+q for p, q in zip(slow, e)]
raw = [((f-s)/s*100 if s != 0 else 0.0) for f, s in zip(fast, slow)]
osc = sma(raw, 1); sig = ema(raw, 13)
out['gmma_osc'] = osc; out['gmma_sig'] = sig
d, a, x = cross_state(osc, sig, osc); out['gmma_dir'] = d; out['gmma_age'] = a
# WT 5m 9/12
src = [(H[i]+L[i]+C[i])/3 for i in range(N)]
esa = ema(src, 9); dd = ema([abs(s-e) for s, e in zip(src, esa)], 9)
ci = [((s-e)/(0.015*q) if q != 0 else 0.0) for s, e, q in zip(src, esa, dd)]
wt1 = ema(ci, 12); wt2 = sma(wt1, 4)
out['wt1'] = wt1; out['wt2'] = wt2
d, a, x = cross_state(wt1, wt2, wt1); out['wt_dir'] = d; out['wt_age'] = a; out['wt_at'] = x
# STCR
rr = rsi(C, 14); raw = []
for i in range(N):
    if i < 13: raw.append(nan); continue
    w = rr[i-13:i+1]
    if any(math.isnan(v) for v in w): raw.append(nan); continue
    hh, ll = max(w), min(w)
    raw.append(nan if hh == ll else 100*(rr[i]-ll)/(hh-ll))
k = sma(raw, 3); dl = sma(k, 3)
out['stcr_k'] = k; out['stcr_d'] = dl
d, a, x = cross_state(k, dl, k); out['stcr_dir'] = d; out['stcr_age'] = a; out['stcr_at'] = x
# ADX 14
up = [nan]+[H[i]-H[i-1] for i in range(1, N)]
dn = [nan]+[-(L[i]-L[i-1]) for i in range(1, N)]
pdm = [nan if math.isnan(u) else (u if (u > q and u > 0) else 0.0) for u, q in zip(up, dn)]
ndm = [nan if math.isnan(q) else (q if (q > u and q > 0) else 0.0) for u, q in zip(up, dn)]
atr = rma(trf(True), 14); rp = rma(pdm, 14); rn = rma(ndm, 14)
dip = [100*p/q for p, q in zip(rp, atr)]; dim = [100*p/q for p, q in zip(rn, atr)]
dx = [100*abs(p-q)/(p+q+0.000001) for p, q in zip(dip, dim)]
out['adx'] = rma(dx, 14); out['dip'] = dip; out['dim'] = dim
# Squeeze
def stdev_pop(x, n):
    o = [nan]*len(x)
    for i in range(n-1, len(x)):
        w = x[i-n+1:i+1]; m = sum(w)/n; o[i] = math.sqrt(sum((v-m)**2 for v in w)/n)
    return o
basis = sma(C, 20); sd = stdev_pop(C, 20)
uBB = [b+1.5*s for b, s in zip(basis, sd)]; lBB = [b-1.5*s for b, s in zip(basis, sd)]
ma = sma(C, 20); rg = sma(trf(False), 20)
uKC = [m+r*1.5 for m, r in zip(ma, rg)]; lKC = [m-r*1.5 for m, r in zip(ma, rg)]
sqzOn = [1 if (a > b and c < d) else 0 for a, b, c, d in zip(lBB, lKC, uBB, uKC)]
hh = [max(H[i-19:i+1]) if i >= 19 else nan for i in range(N)]
ll = [min(L[i-19:i+1]) if i >= 19 else nan for i in range(N)]
smc = sma(C, 20)
xx = [C[i]-((hh[i]+ll[i])/2+smc[i])/2 for i in range(N)]
def linreg_last(w):
    n = len(w); xs = list(range(n)); mx = sum(xs)/n; my = sum(w)/n
    sl = sum((a-mx)*(b-my) for a, b in zip(xs, w))/sum((a-mx)**2 for a in xs)
    return my + sl*(n-1-mx)
val = [linreg_last(xx[i-19:i+1]) if i >= 19 and not any(math.isnan(v) for v in xx[i-19:i+1]) else nan for i in range(N)]
out['sqz_val'] = val; out['sqz_on'] = sqzOn
# Bias with sequential state
def bias(plus, warm=150):
    line = sma(ema(L if plus else H, 3), 9); n = -1; o = []
    for i in range(N):
        ln = line[i]
        if L[i] <= ln and H[i] >= ln: n = 0
        elif n == 0:
            ok = (L[i] > ln) if plus else (H[i] < ln)
            n = 1 if ok else -1
        elif n >= 1: n += 1
        o.append(n if i+1 >= warm else -1)
    return o
out['bias_plus'] = bias(True); out['bias_minus'] = bias(False)
with open('ref.csv', 'w') as f:
    keys = list(out.keys()); w = csv.writer(f); w.writerow(keys)
    for i in range(N): w.writerow([repr(float(out[k][i])) for k in keys])
print('bars', N, 'series', len(out))
