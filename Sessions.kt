package com.scanmaster.engine

import java.time.LocalDate
import java.time.ZoneOffset

/** One OHLCV candle. `t` = candle OPEN time in epoch milliseconds (UTC). */
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

/**
 * Exchange session in IST (UTC+5:30, no DST). Times are minutes after midnight IST.
 * NSE: 09:15-15:30.  MCX (user-confirmed): 09:00-23:30, same calendar day.
 * Holidays are not modelled here: the calendar only maps a timestamp to its session.
 */
class SessionCalendar(val name: String, val openMin: Int, val closeMin: Int) {
    private val istMs = 19_800_000L
    private val dayMs = 86_400_000L

    private fun dayIndex(t: Long) = Math.floorDiv(t + istMs, dayMs)
    fun sessionStart(t: Long): Long = dayIndex(t) * dayMs - istMs + openMin * 60_000L
    fun sessionEnd(t: Long): Long = dayIndex(t) * dayMs - istMs + closeMin * 60_000L
    fun inSession(t: Long) = t >= sessionStart(t) && t < sessionEnd(t)

    /** Trading-day key yyyymmdd in IST. */
    fun tradingDay(t: Long): Int {
        val d = LocalDate.ofEpochDay(dayIndex(t))
        return d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth
    }

    /** Start of the exchange-aligned bucket of `tfMin` minutes containing `t` (0 = whole session). */
    fun bucketStart(t: Long, tfMin: Int): Long {
        val s = sessionStart(t)
        if (tfMin <= 0) return s
        val w = tfMin * 60_000L
        return s + ((t - s) / w) * w
    }

    /** End of that bucket; the last bucket of the session is shortened to the session close. */
    fun bucketEnd(t: Long, tfMin: Int): Long {
        val e = sessionEnd(t)
        if (tfMin <= 0) return e
        return minOf(bucketStart(t, tfMin) + tfMin * 60_000L, e)
    }

    companion object {
        val NSE = SessionCalendar("NSE", 9 * 60 + 15, 15 * 60 + 30)
        val MCX = SessionCalendar("MCX", 9 * 60, 23 * 60 + 30)
    }
}

class AggregateResult(val candles: List<Candle>, val incompleteBuckets: List<Long>)

/**
 * Exchange-session-aware aggregation of base candles into `tfMin`-minute candles (0 = daily).
 * Only COMPLETE buckets are returned (policy: completed candles only). A bucket is complete when it
 * contains exactly the expected number of base candles and the last one ends at the bucket end.
 * Buckets with missing base candles (data gaps) are reported, never silently filled.
 */
fun aggregate(base: List<Candle>, baseMin: Int, tfMin: Int, cal: SessionCalendar): AggregateResult {
    val out = ArrayList<Candle>()
    val bad = ArrayList<Long>()
    val baseMs = baseMin * 60_000L
    var i = 0
    while (i < base.size) {
        val bs = cal.bucketStart(base[i].t, tfMin)
        val be = cal.bucketEnd(base[i].t, tfMin)
        var j = i
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY; var vol = 0.0
        while (j < base.size && cal.bucketStart(base[j].t, tfMin) == bs) {
            hi = maxOf(hi, base[j].h); lo = minOf(lo, base[j].l); vol += base[j].v; j++
        }
        val count = j - i
        val expected = ((be - bs) / baseMs).toInt()
        val lastEnds = base[j - 1].t + baseMs
        if (count == expected && lastEnds == be) out.add(Candle(bs, base[i].o, hi, lo, base[j - 1].c, vol)) else bad.add(bs)
        i = j
    }
    return AggregateResult(out, bad)
}

/** Columnar view of candles of ONE session calendar with session boundary flags. */
class Bars(val candles: List<Candle>, val cal: SessionCalendar, val periodMin: Int) {
    val n = candles.size
    val t = LongArray(n) { candles[it].t }
    val o = Series(n) { candles[it].o }
    val h = Series(n) { candles[it].h }
    val l = Series(n) { candles[it].l }
    val c = Series(n) { candles[it].c }
    val v = Series(n) { candles[it].v }
    val day = IntArray(n) { cal.tradingDay(t[it]) }

    /** First bar of a trading day (or the data's first bar, only if it sits on the session open). */
    val first = BooleanArray(n) { if (it == 0) t[0] == cal.sessionStart(t[0]) else day[it] != day[it - 1] }

    /**
     * Last bar of a session: the next bar belongs to another day, or (for the final bar of data)
     * the bar ends exactly at the session close.
     */
    val last = BooleanArray(n) {
        if (it < n - 1) day[it] != day[it + 1] else t[it] + periodMin * 60_000L == cal.sessionEnd(t[it])
    }
}
