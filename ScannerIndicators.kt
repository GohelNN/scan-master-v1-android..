package com.scanmaster.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Ports of the scanner columns from "Scan Master Set 1 (15.10.26)" (main script).
 * All functions run bar by bar over COMPLETED 5-minute candles, so the Pine guard
 * `time_close <= timenow` is always satisfied. Values at index i are the state after bar i closed.
 * SF5 is intentionally NOT ported.
 */

// ------------------------------------------------------------------ DIR  (f_direction)
/**
 * 1 / -1 / 0 per bar. The value is decided on the first completed 5M candle of each session by comparing its
 * close with the high/low of the previous session's highest-volume candle (first and last candles excluded,
 * earliest candle wins volume ties because the comparison is strict). It is held for the rest of the session.
 * Callers must show "-" if the latest completed bar belongs to an earlier session than today.
 */
fun directionDecider(b: Bars): IntArray {
    val out = IntArray(b.n)
    var known = false; var bars = 0
    var bestVol = 0.0; var bestH = NA; var bestL = NA
    var direction = 0
    for (i in 0 until b.n) {
        if (b.first[i]) {
            direction = if (!bestH.isNaN()) (if (b.c[i] > bestH) 1 else if (b.c[i] < bestL) -1 else 0) else 0
            bestVol = 0.0; bestH = NA; bestL = NA; bars = 0; known = true
        } else {
            bars += 1
            if (known && bars >= 2 && i >= 1 && !b.v[i - 1].isNaN() && b.v[i - 1] > 0 && (bestH.isNaN() || b.v[i - 1] > bestVol)) {
                bestVol = b.v[i - 1]; bestH = b.h[i - 1]; bestL = b.l[i - 1]
            }
        }
        out[i] = direction
    }
    return out
}

// ------------------------------------------------------------------ Opn, 5M, STK opening values
/** Opn: 1 if today's open > previous session close, -1 if <, 0 if equal, null if unknown. */
fun openDirection(todayOpen: Double, prevClose: Double): Int? =
    if (todayOpen.isNaN() || prevClose.isNaN()) null else if (todayOpen > prevClose) 1 else if (todayOpen < prevClose) -1 else 0

/** Signed opening gap % : (open - prevClose) / prevClose * 100 (the STK "G" value). */
fun gapPct(todayOpen: Double, prevClose: Double): Double =
    if (todayOpen.isNaN() || prevClose.isNaN() || prevClose == 0.0) NA else (todayOpen - prevClose) / prevClose * 100.0

/** Unsigned range % : (high - low) / low * 100. */
fun rangePct(high: Double, low: Double): Double = if (high.isNaN() || low.isNaN() || low == 0.0) NA else (high - low) / low * 100.0

/**
 * Opening-candle ranges for a session starting at index `s` of 5M bars. `five` = first 5M candle.
 * `fifteen` = first three 5M candles, published only if all three are present and contiguous (source rule:
 * the last bar must close exactly at start+15min). `bullish*` = candle colour (close >= open) for the STK arrow
 * (for 15 it is the colour of the combined window: last close vs first open).
 */
class OpeningStats(val five: Double, val fifteen: Double, val fiveBullish: Boolean?, val fifteenBullish: Boolean?)

fun openingStats(b: Bars, s: Int): OpeningStats {
    if (s < 0 || s >= b.n) return OpeningStats(NA, NA, null, null)
    val start = b.t[s]; val ms = b.periodMin * 60_000L
    val five = rangePct(b.h[s], b.l[s])
    val fiveBull = if (b.c[s] == b.o[s]) null else b.c[s] > b.o[s]
    val k = 15 / b.periodMin
    var fifteen = NA; var fBull: Boolean? = null
    if (s + k - 1 < b.n && (0 until k).all { b.t[s + it] == start + it * ms } && b.day[s + k - 1] == b.day[s]) {
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        for (j in s until s + k) { hi = max(hi, b.h[j]); lo = min(lo, b.l[j]) }
        fifteen = rangePct(hi, lo)
        val co = b.c[s + k - 1] - b.o[s]
        fBull = if (co == 0.0) null else co > 0
    }
    return OpeningStats(five, fifteen, fiveBull, fBull)
}

// ------------------------------------------------------------------ VM / IB  (f_levels)
class LevelsResult(val dir: IntArray, val count: IntArray, val rt: IntArray)

/**
 * volumeMode = true -> VM (three same-colour candles with strictly rising volume).
 * volumeMode = false -> IB (mother-bar setups A and B, wick break).
 * Counts start at 1 on the closed candle right after a line-crossing candle; RT = second or later
 * consecutive same-direction setup. Session reset on every first bar.
 */
fun levelsSignal(b: Bars, volumeMode: Boolean): LevelsResult {
    val n = b.n
    val outDir = IntArray(n); val outCount = IntArray(n); val outRt = IntArray(n)
    var known = false; var bars = 0; var done = false; var pending = false; var pendingDir = 0
    var pendingH = NA; var pendingL = NA; var markH = NA; var markL = NA
    var dir = 0; var count = 0; var armedUp = false; var armedDown = false
    var lastDir = 0; var runs = 0; var rt = 0
    fun o(i: Int, k: Int) = if (i - k >= 0) b.o[i - k] else NA
    fun c(i: Int, k: Int) = if (i - k >= 0) b.c[i - k] else NA
    fun h(i: Int, k: Int) = if (i - k >= 0) b.h[i - k] else NA
    fun l(i: Int, k: Int) = if (i - k >= 0) b.l[i - k] else NA
    fun v(i: Int, k: Int) = if (i - k >= 0) b.v[i - k] else NA
    for (i in 0 until n) {
        val first = b.first[i]
        if (first) {
            known = true; bars = 0; done = false; pending = false; markH = NA; markL = NA
            dir = 0; count = 0; armedUp = false; armedDown = false; lastDir = 0; runs = 0; rt = 0
        }
        bars += 1
        val open = b.o[i]; val close = b.c[i]; val high = b.h[i]; val low = b.l[i]; val vol = b.v[i]
        if (volumeMode) {
            val up = close > open && c(i, 1) > o(i, 1) && c(i, 2) > o(i, 2)
            val down = close < open && c(i, 1) < o(i, 1) && c(i, 2) < o(i, 2)
            val rising = v(i, 2) < v(i, 1) && v(i, 1) < vol
            if (known && bars >= 3 && !done && !pending && (up || down) && rising) {
                pending = true; pendingDir = if (up) 1 else -1
                pendingH = max(high, max(h(i, 1), h(i, 2))); pendingL = min(low, min(l(i, 1), l(i, 2)))
            } else if (pending && !done) {
                val continues = (if (pendingDir == 1) close > open else close < open) && vol > v(i, 1)
                pending = false
                if (!continues) { done = true; markH = pendingH; markL = pendingL }
            }
        } else {
            val a = c(i, 1) > o(i, 1) && close < open && !first &&
                open <= h(i, 1) && open >= l(i, 1) && close <= h(i, 1) && close >= l(i, 1) &&
                high <= h(i, 1) && low < l(i, 1)
            val bb = c(i, 1) < o(i, 1) && close > open && !first &&
                open <= h(i, 1) && open >= l(i, 1) && close <= h(i, 1) && close >= l(i, 1) &&
                low >= l(i, 1) && high > h(i, 1)
            if (known && !done && (a || bb)) { done = true; markH = h(i, 1); markL = l(i, 1) }
        }
        // Strict crossing: touching a line is not a valid setup.
        val crossUp = done && low < markH && high > markH
        val crossDown = done && low < markL && high > markL
        val above = done && low > markH
        val below = done && high < markL
        if (crossUp || crossDown) {
            armedUp = crossUp; armedDown = crossDown; dir = 0; count = 0
        } else {
            if (armedUp || armedDown) {
                if (armedUp && above) { dir = 1; count = 1 }
                else if (armedDown && below) { dir = -1; count = 1 }
                else { dir = 0; count = 0 }
                if (dir != 0) { runs = if (dir == lastDir) runs + 1 else 1; lastDir = dir; rt = if (runs >= 2) dir else 0 }
                armedUp = false; armedDown = false
            } else if (dir == 1 && above) count += 1
            else if (dir == -1 && below) count += 1
            else { dir = 0; count = 0 }
        }
        outDir[i] = dir; outCount[i] = count; outRt[i] = rt
    }
    return LevelsResult(outDir, outCount, outRt)
}

// ------------------------------------------------------------------ MP / POC  (f_getPocData)
class PocResult(
    val poc: Series,            // active POC (from the previous session) per bar
    val touchedToday: BooleanArray,
    val dir: IntArray,
    val count: IntArray,
    val rt: IntArray,
    val pctFromPoc: Series,     // (close - poc) / poc * 100
)

/**
 * Original custom Market Profile. `rows` = 24 buckets across the session's full high/low. Each 5M bar's volume
 * is split EQUALLY across the buckets between its low-bucket and high-bucket (difference array), not by overlap
 * fraction. POC = lowest bucket holding the maximum volume (strict >), centre of the bucket.
 * The POC computed at the last bar of a session becomes active at the first bar of the next session.
 */
fun marketProfile(b: Bars, rows: Int = 24): PocResult {
    val n = b.n
    val pocS = newSeries(n); val touchedS = BooleanArray(n); val dirS = IntArray(n); val cntS = IntArray(n)
    val rtS = IntArray(n); val pctS = newSeries(n)
    var liveHigh = NA; var liveLow = NA
    val rawH = ArrayList<Double>(); val rawL = ArrayList<Double>(); val rawV = ArrayList<Double>()
    var pendingPoc = NA; var activePoc = NA
    var touched = false; var pending = false
    var activeDir = 0; var activeCount = 0; var lastDir = 0; var rt = 0
    fun bucket(price: Double, lo: Double, step: Double): Int =
        max(0.0, min((rows - 1).toDouble(), floor((price - lo) / step))).toInt()
    for (i in 0 until n) {
        val high = b.h[i]; val low = b.l[i]; val close = b.c[i]
        if (b.first[i] && i > 0) {
            activePoc = pendingPoc; touched = false; pending = false
            activeDir = 0; activeCount = 0; lastDir = 0; rt = 0
            liveHigh = high; liveLow = low
            rawH.clear(); rawL.clear(); rawV.clear()
        } else {
            liveHigh = if (liveHigh.isNaN()) high else max(liveHigh, high)
            liveLow = if (liveLow.isNaN()) low else min(liveLow, low)
        }
        rawH.add(high); rawL.add(low); rawV.add(if (b.v[i].isNaN()) 0.0 else b.v[i])
        if (b.last[i]) {
            val range = liveHigh - liveLow
            val step = range / rows
            if (rawH.isNotEmpty() && range > 0 && step > 0) {
                val diff = DoubleArray(rows + 1)
                for (k in rawH.indices) {
                    val lb = bucket(rawL[k], liveLow, step); val hb = bucket(rawH[k], liveLow, step)
                    val span = hb - lb + 1
                    val vpb = if (span > 0) rawV[k] / span else 0.0
                    diff[lb] += vpb
                    if (hb + 1 < diff.size) diff[hb + 1] -= vpb
                }
                var best = 0; var bestVol = -1.0; var running = 0.0
                for (k in 0 until rows) { running += diff[k]; if (running > bestVol) { bestVol = running; best = k } }
                pendingPoc = liveLow + best * step + step * 0.5
            }
        }
        val onBar = !activePoc.isNaN() && low <= activePoc && high >= activePoc
        if (onBar) {
            if (activeDir != 0) rt = activeDir
            touched = true; pending = true; activeDir = 0; activeCount = 0
        } else if (touched && pending) {
            if (low > activePoc) { activeDir = 1; activeCount = 1; pending = false }
            else if (high < activePoc) { activeDir = -1; activeCount = 1; pending = false }
        } else if (touched && activeDir == 1) {
            if (low > activePoc) activeCount += 1 else { activeDir = 0; activeCount = 0; pending = false }
        } else if (touched && activeDir == -1) {
            if (high < activePoc) activeCount += 1 else { activeDir = 0; activeCount = 0; pending = false }
        }
        if (activeDir != 0) { if (lastDir != 0 && lastDir != activeDir) rt = 0; lastDir = activeDir }
        pocS[i] = activePoc; touchedS[i] = touched; dirS[i] = activeDir; cntS[i] = activeCount; rtS[i] = rt
        pctS[i] = if (!activePoc.isNaN() && activePoc != 0.0) (close - activePoc) / activePoc * 100.0 else NA
    }
    return PocResult(pocS, touchedS, dirS, cntS, rtS, pctS)
}

// ------------------------------------------------------------------ H  (f_hour)
/**
 * Exchange-aligned hourly trend from 5M bars. Only hours observed from their start enter the SMA(10) history.
 * Direction updates once per completed hour (the final hour of the session is shorter) and is held in between.
 * bull: close > SMA10(close) and close > previous hour high; bear: mirror; else 0.
 */
fun hourlyTrend(b: Bars): IntArray {
    val out = IntArray(b.n)
    val closes = ArrayList<Double>()
    var prevBucket = Long.MIN_VALUE
    var fullHour = false; var hourHigh = NA; var hourLow = NA
    var prevHigh = NA; var prevLow = NA; var direction = 0
    for (i in 0 until b.n) {
        val bucket = b.cal.bucketStart(b.t[i], 60)
        val newHour = bucket != prevBucket
        if (newHour) { fullHour = b.t[i] == bucket; hourHigh = b.h[i]; hourLow = b.l[i] }
        else { hourHigh = max(hourHigh, b.h[i]); hourLow = min(hourLow, b.l[i]) }
        prevBucket = bucket
        val barEnd = b.t[i] + b.periodMin * 60_000L
        if (fullHour && barEnd == b.cal.bucketEnd(b.t[i], 60)) {
            closes.add(b.c[i]); if (closes.size > 10) closes.removeAt(0)
            val avg = if (closes.size == 10) closes.sum() / 10.0 else NA
            direction = if (b.c[i] > avg && b.c[i] > prevHigh) 1 else if (b.c[i] < avg && b.c[i] < prevLow) -1 else 0
            prevHigh = hourHigh; prevLow = hourLow
        }
        out[i] = direction
    }
    return out
}

// ------------------------------------------------------------------ Bias+ / Bias-  (f_bias)
/**
 * Returns n per bar: -1 = no qualified setup, 0 = touch of the line, k>=1 = bars since the first bar that
 * cleared the line on the correct side. Output is -1 until `warmup` bars have been seen. No daily reset.
 * NOTE: the result depends on how much history is supplied (source used up to 1500 5M bars).
 */
fun bias(b: Bars, plus: Boolean, warmup: Int = 150): IntArray {
    val line = Pine.sma(Pine.ema(if (plus) b.l else b.h, 3), 9)
    val out = IntArray(b.n)
    var n = -1
    for (i in 0 until b.n) {
        val ln = line[i]
        if (b.l[i] <= ln && b.h[i] >= ln) n = 0
        else if (n == 0) n = if (if (plus) b.l[i] > ln else b.h[i] < ln) 1 else -1
        else if (n >= 1) n += 1
        out[i] = if (i + 1 >= warmup) n else -1
    }
    return out
}
