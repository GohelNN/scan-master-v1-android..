package com.scanmaster.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

typealias Series = DoubleArray

const val NA: Double = Double.NaN

fun newSeries(n: Int): Series = DoubleArray(n) { NA }

/**
 * Pine Script v6 compatible primitives. `NA` is NaN; every comparison against NA is false,
 * which matches Pine's behaviour for na in conditions.
 * Each function documents the Pine rule it reproduces.
 */
object Pine {

    /** ta.sma: na until `len` bars exist; na if any value in the window is na. */
    fun sma(src: Series, len: Int): Series {
        val out = newSeries(src.size)
        for (i in len - 1 until src.size) {
            var s = 0.0
            var ok = true
            for (j in i - len + 1..i) {
                val v = src[j]
                if (v.isNaN()) { ok = false; break }
                s += v
            }
            if (ok) out[i] = s / len
        }
        return out
    }

    /** ta.ema: alpha = 2/(len+1); seeded with the first non-na source value (NOT an SMA seed). */
    fun ema(src: Series, len: Int): Series {
        val out = newSeries(src.size)
        val a = 2.0 / (len + 1)
        var prev = NA
        for (i in src.indices) {
            val v = src[i]
            val r = when {
                v.isNaN() -> NA
                prev.isNaN() -> v
                else -> a * v + (1 - a) * prev
            }
            out[i] = r
            prev = r
        }
        return out
    }

    /** ta.rma: alpha = 1/len; seeded with ta.sma(src, len) on the first bar where it exists. */
    fun rma(src: Series, len: Int): Series {
        val out = newSeries(src.size)
        val a = 1.0 / len
        val seed = sma(src, len)
        var prev = NA
        for (i in src.indices) {
            val r = if (prev.isNaN()) seed[i] else a * src[i] + (1 - a) * prev
            out[i] = r
            prev = r
        }
        return out
    }

    /** ta.change */
    fun change(src: Series): Series {
        val out = newSeries(src.size)
        for (i in 1 until src.size) out[i] = src[i] - src[i - 1]
        return out
    }

    /** ta.rsi: RMA of gains / losses, with Pine's 100 / 0 edge cases. */
    fun rsi(src: Series, len: Int): Series {
        val ch = change(src)
        val gain = Series(src.size) { max(ch[it], 0.0) }
        val loss = Series(src.size) { -min(ch[it], 0.0) }
        val up = rma(gain, len)
        val down = rma(loss, len)
        return Series(src.size) {
            val u = up[it]; val d = down[it]
            when {
                d == 0.0 -> 100.0
                u == 0.0 -> 0.0
                else -> 100.0 - 100.0 / (1.0 + u / d)
            }
        }
    }

    /**
     * ta.tr(handleNa). handleNa=false (plain `tr`): na on the first bar.
     * handleNa=true: first bar = high - low.
     */
    fun tr(high: Series, low: Series, close: Series, handleNa: Boolean): Series {
        val out = newSeries(high.size)
        for (i in high.indices) {
            out[i] = if (i == 0) {
                if (handleNa) high[0] - low[0] else NA
            } else {
                max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
            }
        }
        return out
    }

    /** ta.atr = rma(tr(true), len) */
    fun atr(high: Series, low: Series, close: Series, len: Int): Series =
        rma(tr(high, low, close, true), len)

    /** ta.stdev with biased = true (population), the Pine default. */
    fun stdev(src: Series, len: Int): Series {
        val out = newSeries(src.size)
        for (i in len - 1 until src.size) {
            var s = 0.0
            var ok = true
            for (j in i - len + 1..i) { if (src[j].isNaN()) { ok = false; break }; s += src[j] }
            if (!ok) continue
            val m = s / len
            var q = 0.0
            for (j in i - len + 1..i) { val d = src[j] - m; q += d * d }
            out[i] = sqrt(q / len)
        }
        return out
    }

    /** ta.highest: na until `len` bars exist. */
    fun highest(src: Series, len: Int): Series {
        val out = newSeries(src.size)
        for (i in len - 1 until src.size) {
            var m = Double.NEGATIVE_INFINITY
            var ok = true
            for (j in i - len + 1..i) { if (src[j].isNaN()) { ok = false; break }; if (src[j] > m) m = src[j] }
            if (ok) out[i] = m
        }
        return out
    }

    /** ta.lowest */
    fun lowest(src: Series, len: Int): Series {
        val out = newSeries(src.size)
        for (i in len - 1 until src.size) {
            var m = Double.POSITIVE_INFINITY
            var ok = true
            for (j in i - len + 1..i) { if (src[j].isNaN()) { ok = false; break }; if (src[j] < m) m = src[j] }
            if (ok) out[i] = m
        }
        return out
    }

    /**
     * ta.stoch(src, high, low, len) = 100 * (src - lowest(low)) / (highest(high) - lowest(low)).
     * Zero range is treated as na. (ASSUMPTION - not yet confirmed against TradingView.)
     */
    fun stoch(src: Series, high: Series, low: Series, len: Int): Series {
        val hh = highest(high, len)
        val ll = lowest(low, len)
        return Series(src.size) {
            val d = hh[it] - ll[it]
            if (d.isNaN() || d == 0.0) NA else 100.0 * (src[it] - ll[it]) / d
        }
    }

    /** ta.linreg(src, len, offset): value of the least-squares line at the latest bar (minus offset). */
    fun linreg(src: Series, len: Int, offset: Int = 0): Series {
        val out = newSeries(src.size)
        val n = len.toDouble()
        var sx = 0.0; var sxx = 0.0
        for (x in 0 until len) { sx += x; sxx += x.toDouble() * x }
        for (i in len - 1 until src.size) {
            var sy = 0.0; var sxy = 0.0; var ok = true
            for (k in 0 until len) {
                val y = src[i - len + 1 + k]
                if (y.isNaN()) { ok = false; break }
                sy += y; sxy += k * y
            }
            if (!ok) continue
            val slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
            val intercept = (sy - slope * sx) / n
            out[i] = intercept + slope * (len - 1 - offset)
        }
        return out
    }

    /** ta.crossover: a > b now and a <= b on the previous bar. na => false. */
    fun crossover(a: Series, b: Series): BooleanArray =
        BooleanArray(a.size) { it > 0 && a[it] > b[it] && a[it - 1] <= b[it - 1] }

    /** ta.crossunder */
    fun crossunder(a: Series, b: Series): BooleanArray =
        BooleanArray(a.size) { it > 0 && a[it] < b[it] && a[it - 1] >= b[it - 1] }

    fun hlc3(h: Series, l: Series, c: Series): Series = Series(h.size) { (h[it] + l[it] + c[it]) / 3.0 }
}
