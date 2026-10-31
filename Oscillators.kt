package com.scanmaster.engine

import kotlin.math.abs

enum class Strength { SINGLE, DOUBLE, TRIPLE }

/**
 * Persistent cross state, as in the Pine source (`var gdir`, `var gbars`):
 *  - bullish cross: dir = 1, age = 0, valueAtCross = v
 *  - bearish cross: dir = -1, age = 0, valueAtCross = v
 *  - otherwise, if a cross has ever occurred: age += 1
 * Age 0 = the cross happened on this (completed) bar. dir 0 = no cross seen yet.
 */
class CrossSeries(val dir: IntArray, val age: IntArray, val valueAtCross: Series)

fun trackCross(bull: BooleanArray, bear: BooleanArray, v: Series): CrossSeries {
    val n = bull.size
    val dir = IntArray(n); val age = IntArray(n); val at = newSeries(n)
    var d = 0; var a = 0; var x = NA
    for (i in 0 until n) {
        if (bull[i]) { d = 1; a = 0; x = v[i] }
        else if (bear[i]) { d = -1; a = 0; x = v[i] }
        else if (d != 0) a += 1
        dir[i] = d; age[i] = a; at[i] = x
    }
    return CrossSeries(dir, age, at)
}

// ------------------------------------------------------------------ GMMA
enum class GmmaType { GUPPY, SUPER_GUPPY }

class GmmaResult(val osc: Series, val sig: Series, val cross: CrossSeries)

/** Source: GMMA file `f_gmma` (anchor multiplier fixed at 1). Zero slow-sum guard as in the pane version. */
fun gmma(src: Series, type: GmmaType = GmmaType.GUPPY, smooth: Int = 1, signal: Int = 13): GmmaResult {
    val n = src.size
    val fastLens: IntArray; val slowLens: IntArray
    val avgDiv: Boolean
    if (type == GmmaType.GUPPY) {
        fastLens = intArrayOf(3, 5, 8, 10, 12, 15); slowLens = intArrayOf(30, 35, 40, 45, 50, 60); avgDiv = false
    } else {
        fastLens = IntArray(11) { 3 + 2 * it }          // 3,5,...,23
        slowLens = IntArray(16) { 25 + 3 * it }         // 25,28,...,70
        avgDiv = true
    }
    fun total(lens: IntArray): Series {
        val s = Series(n)
        for (l in lens) { val e = Pine.ema(src, l); for (i in 0 until n) s[i] += e[i] }
        if (avgDiv) for (i in 0 until n) s[i] = s[i] / lens.size.toDouble()
        return s
    }
    val f = total(fastLens); val sl = total(slowLens)
    val raw = Series(n) { if (sl[it] != 0.0) (f[it] - sl[it]) / sl[it] * 100.0 else 0.0 }
    val osc = Pine.sma(raw, smooth)
    val sig = Pine.ema(raw, signal)
    return GmmaResult(osc, sig, trackCross(Pine.crossover(osc, sig), Pine.crossunder(osc, sig), osc))
}

// ------------------------------------------------------------------ WaveTrend
class WtResult(val wt1: Series, val wt2: Series, val cross: CrossSeries)

/** Source: `calc_wt_tri`. wt2 = SMA(wt1, maLen=4). Parameters per timeframe are supplied by the caller. */
fun waveTrend(h: Series, l: Series, c: Series, ch: Int, avg: Int, maLen: Int = 4): WtResult {
    val n = c.size
    val src = Pine.hlc3(h, l, c)
    val esa = Pine.ema(src, ch)
    val d = Pine.ema(Series(n) { abs(src[it] - esa[it]) }, ch)
    val ci = Series(n) { if (d[it] != 0.0) (src[it] - esa[it]) / (0.015 * d[it]) else 0.0 }
    val wt1 = Pine.ema(ci, avg)
    val wt2 = Pine.sma(wt1, maLen)
    return WtResult(wt1, wt2, trackCross(Pine.crossover(wt1, wt2), Pine.crossunder(wt1, wt2), wt1))
}

/** Confirmed WT timeframe parameters. Below 5 minutes WaveTrend is NOT calculated (user decision). */
fun wtParams(tfMinutes: Int): Pair<Int, Int>? = when (tfMinutes) {
    5 -> 9 to 12
    15 -> 10 to 21
    60 -> 10 to 21
    1440 -> 12 to 24
    else -> null
}

/** Spec 8.2. `dir` = cross direction, `wt1` = WT1 on the cross bar. */
fun wtStrength(dir: Int, wt1: Double): Strength? = when {
    dir == 1 -> if (wt1 <= -53) Strength.TRIPLE else if (wt1 <= 0) Strength.DOUBLE else Strength.SINGLE
    dir == -1 -> if (wt1 >= 53) Strength.TRIPLE else if (wt1 >= 0) Strength.DOUBLE else Strength.SINGLE
    else -> null
}

// ------------------------------------------------------------------ Stochastic RSI (STCR)
class StcrResult(val k: Series, val d: Series, val cross: CrossSeries)

/** Source: `calc_stcr`. K = SMA(stoch(rsi), kSmooth), D = SMA(K, dSmooth). */
fun stochRsi(c: Series, rsiLen: Int = 14, stochLen: Int = 14, kSmooth: Int = 3, dSmooth: Int = 3): StcrResult {
    val r = Pine.rsi(c, rsiLen)
    val raw = Pine.stoch(r, r, r, stochLen)
    val k = Pine.sma(raw, kSmooth)
    val d = Pine.sma(k, dSmooth)
    return StcrResult(k, d, trackCross(Pine.crossover(k, d), Pine.crossunder(k, d), k))
}

/** Spec 8.3. `k` = K on the cross bar. */
fun stcrStrength(dir: Int, k: Double): Strength? = when {
    dir == 1 -> if (k < 20) Strength.TRIPLE else if (k <= 80) Strength.DOUBLE else Strength.SINGLE
    dir == -1 -> if (k > 80) Strength.TRIPLE else if (k >= 20) Strength.DOUBLE else Strength.SINGLE
    else -> null
}

// ------------------------------------------------------------------ MRSI
class MrsiResult(val rsiFast: Series, val rsiSlow: Series, val cross: CrossSeries)

/** Trigger: RSI(20) crossing RSI(100) only (user override of the 8/12/16 script defaults). */
fun mrsi(c: Series, fast: Int = 20, slow: Int = 100): MrsiResult {
    val f = Pine.rsi(c, fast); val s = Pine.rsi(c, slow)
    return MrsiResult(f, s, trackCross(Pine.crossover(f, s), Pine.crossunder(f, s), f))
}

/** DETAIL ONLY: RSI20 vs SMA14(RSI20). Never feeds a trigger, filter or event log. */
fun rsiSmaDetailCross(c: Series, rsiLen: Int = 20, smaLen: Int = 14): CrossSeries {
    val r = Pine.rsi(c, rsiLen); val m = Pine.sma(r, smaLen)
    return trackCross(Pine.crossover(r, m), Pine.crossunder(r, m), r)
}

// ------------------------------------------------------------------ ADX / DI
class AdxResult(val adx: Series, val diPlus: Series, val diMinus: Series)

/** Source: `calc_adx_di`, including the 0.000001 stabiliser and ta.tr(true). */
fun adxDi(h: Series, l: Series, c: Series, len: Int = 14): AdxResult {
    val n = c.size
    val up = Pine.change(h)
    val dn = Series(n) { if (it == 0) NA else -(l[it] - l[it - 1]) }
    val pdm = Series(n) { if (up[it].isNaN()) NA else if (up[it] > dn[it] && up[it] > 0) up[it] else 0.0 }
    val ndm = Series(n) { if (dn[it].isNaN()) NA else if (dn[it] > up[it] && dn[it] > 0) dn[it] else 0.0 }
    val atrv = Pine.rma(Pine.tr(h, l, c, true), len)
    val rp = Pine.rma(pdm, len); val rn = Pine.rma(ndm, len)
    val dip = Series(n) { 100.0 * rp[it] / atrv[it] }
    val dim = Series(n) { 100.0 * rn[it] / atrv[it] }
    val dx = Series(n) { 100.0 * abs(dip[it] - dim[it]) / (dip[it] + dim[it] + 0.000001) }
    return AdxResult(Pine.rma(dx, len), dip, dim)
}

// ------------------------------------------------------------------ Squeeze Momentum (LazyBear)
class SqueezeResult(
    val value: Series, val sqzOn: BooleanArray, val sqzOff: BooleanArray, val noSqz: BooleanArray,
    /** 1 lime, 2 green, -1 red, -2 maroon (histogram colour states) */
    val barState: IntArray,
)

/**
 * Port of `Squez momentum.txt`. SOURCE QUIRK PRESERVED: Bollinger deviation = multKC * stdev
 * (the BB mult input is declared but unused). `tr` is the plain built-in (na on bar 0).
 */
fun squeezeMomentum(
    h: Series, l: Series, c: Series,
    length: Int = 20, lengthKC: Int = 20, multKC: Double = 1.5, useTrueRange: Boolean = true,
): SqueezeResult {
    val n = c.size
    val basis = Pine.sma(c, length)
    val sd = Pine.stdev(c, length)
    val dev = Series(n) { multKC * sd[it] }
    val upperBB = Series(n) { basis[it] + dev[it] }
    val lowerBB = Series(n) { basis[it] - dev[it] }
    val ma = Pine.sma(c, lengthKC)
    val range = if (useTrueRange) Pine.tr(h, l, c, false) else Series(n) { h[it] - l[it] }
    val rangema = Pine.sma(range, lengthKC)
    val upperKC = Series(n) { ma[it] + rangema[it] * multKC }
    val lowerKC = Series(n) { ma[it] - rangema[it] * multKC }
    val on = BooleanArray(n) { lowerBB[it] > lowerKC[it] && upperBB[it] < upperKC[it] }
    val off = BooleanArray(n) { lowerBB[it] < lowerKC[it] && upperBB[it] > upperKC[it] }
    val no = BooleanArray(n) { !on[it] && !off[it] }
    val hh = Pine.highest(h, lengthKC); val ll = Pine.lowest(l, lengthKC); val sm = Pine.sma(c, lengthKC)
    val x = Series(n) { c[it] - ((hh[it] + ll[it]) / 2.0 + sm[it]) / 2.0 }
    val v = Pine.linreg(x, lengthKC, 0)
    val st = IntArray(n) {
        val prev = if (it == 0 || v[it - 1].isNaN()) 0.0 else v[it - 1]   // nz(val[1])
        if (v[it] > 0) (if (v[it] > prev) 1 else 2) else (if (v[it] < prev) -1 else -2)
    }
    return SqueezeResult(v, on, off, no, st)
}

// ------------------------------------------------------------------ Detail-panel indicators (standard inputs)
class MacdResult(val macd: Series, val signal: Series, val hist: Series)

/** Standard 12/26/9 (no attached script defines MACD; inputs documented as approved defaults). */
fun macd(c: Series, fast: Int = 12, slow: Int = 26, sig: Int = 9): MacdResult {
    val f = Pine.ema(c, fast); val s = Pine.ema(c, slow)
    val m = Series(c.size) { f[it] - s[it] }
    val sg = Pine.ema(m, sig)
    return MacdResult(m, sg, Series(c.size) { m[it] - sg[it] })
}

class BollingerResult(val basis: Series, val upper: Series, val lower: Series)

/** Standard 20 / 2.0 (conventional multiplier; distinct from the Squeeze script's quirk). */
fun bollinger(c: Series, len: Int = 20, mult: Double = 2.0): BollingerResult {
    val b = Pine.sma(c, len); val d = Pine.stdev(c, len)
    return BollingerResult(b, Series(c.size) { b[it] + mult * d[it] }, Series(c.size) { b[it] - mult * d[it] })
}

/** Session-anchored VWAP on HLC3; resets when `firstOfSession` is true. */
fun vwap(h: Series, l: Series, c: Series, v: Series, firstOfSession: BooleanArray): Series {
    val out = newSeries(c.size)
    var pv = 0.0; var vv = 0.0
    for (i in c.indices) {
        if (firstOfSession[i]) { pv = 0.0; vv = 0.0 }
        pv += (h[i] + l[i] + c[i]) / 3.0 * v[i]; vv += v[i]
        out[i] = if (vv > 0) pv / vv else NA
    }
    return out
}

enum class EmaTrend { STRONG_BULL, WEAK_BULL, STRONG_BEAR, WEAK_BEAR, NEUTRAL }

/** Spec 10.9 on the latest COMPLETED candle close. */
fun emaTrendState(close: Double, ema13: Double, ema50: Double): EmaTrend = when {
    close > ema13 && ema13 > ema50 -> EmaTrend.STRONG_BULL
    ema13 > ema50 && close <= ema13 -> EmaTrend.WEAK_BULL
    close < ema13 && ema13 < ema50 -> EmaTrend.STRONG_BEAR
    ema13 < ema50 && close >= ema13 -> EmaTrend.WEAK_BEAR
    else -> EmaTrend.NEUTRAL
}

/** Signed % distance of a LIVE price from the last completed EMA50. */
fun distanceToEma50Pct(livePrice: Double, ema50: Double): Double =
    if (ema50 == 0.0 || ema50.isNaN()) NA else (livePrice - ema50) / ema50 * 100.0
