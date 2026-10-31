package com.scanmaster.engine

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * Plain self-test runner (no JUnit needed, so it runs anywhere a JVM exists).
 *   args[0] (optional) = directory holding bars.csv + ref.csv produced by tools/xcheck/ref.py
 * IMPORTANT: ref.csv comes from an independent Python port of the SAME Pine formulas. It catches porting
 * mistakes. It is NOT TradingView data, so passing here never means "matches TradingView".
 */
var passed = 0
var failed = 0
val failures = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; failures.add("$name $detail") }
}
fun near(a: Double, b: Double, tol: Double = 1e-9) = (a.isNaN() && b.isNaN()) || abs(a - b) <= tol * maxOf(1.0, abs(b))

fun istMs(y: Int, m: Int, d: Int, hh: Int, mm: Int): Long =
    LocalDateTime.of(y, m, d, hh, mm).toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

/** Build 5M NSE candles for one day from (o,h,l,c,v) tuples starting 09:15. */
fun day(y: Int, m: Int, d: Int, rows: List<DoubleArray>, startH: Int = 9, startM: Int = 15): List<Candle> {
    val t0 = istMs(y, m, d, startH, startM)
    return rows.mapIndexed { i, r -> Candle(t0 + i * 300_000L, r[0], r[1], r[2], r[3], r[4]) }
}
fun r(o: Double, h: Double, l: Double, c: Double, v: Double = 100.0) = doubleArrayOf(o, h, l, c, v)

fun main(args: Array<String>) {
    primitiveTests()
    oscillatorTests()
    sessionTests()
    scannerScenarioTests()
    if (args.isNotEmpty()) crossCheck(File(args[0]))
    println("PASSED: $passed   FAILED: $failed")
    failures.forEach { println("  FAIL: $it") }
    exitProcess(if (failed == 0) 0 else 1)
}

fun primitiveTests() {
    val e = Pine.ema(doubleArrayOf(1.0, 2.0, 3.0), 3)
    check("ema seed", near(e[0], 1.0) && near(e[1], 1.5) && near(e[2], 2.25))
    val rm = Pine.rma(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 3)
    check("rma seed=sma", rm[0].isNaN() && rm[1].isNaN() && near(rm[2], 2.0) && near(rm[3], 2.0 * 2 / 3 + 4.0 / 3))
    val s = Pine.sma(doubleArrayOf(1.0, NA, 3.0, 5.0, 7.0), 2)
    check("sma na window", s[1].isNaN() && s[2].isNaN() && near(s[3], 4.0) && near(s[4], 6.0))
    check("stdev population", near(Pine.stdev(doubleArrayOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 8)[7], 2.0))
    val lin = Pine.linreg(doubleArrayOf(1.0, 3.0, 5.0, 7.0, 9.0), 5, 0)
    check("linreg exact line", near(lin[4], 9.0))
    check("linreg offset", near(Pine.linreg(doubleArrayOf(1.0, 3.0, 5.0, 7.0, 9.0), 5, 1)[4], 7.0))
    val up = DoubleArray(30) { 100.0 + it }
    check("rsi all gains = 100", near(Pine.rsi(up, 14)[29], 100.0))
    val dn = DoubleArray(30) { 100.0 - it }
    check("rsi all losses = 0", near(Pine.rsi(dn, 14)[29], 0.0))
    check("tr first bar na / handleNa", Pine.tr(doubleArrayOf(5.0), doubleArrayOf(3.0), doubleArrayOf(4.0), false)[0].isNaN() &&
        near(Pine.tr(doubleArrayOf(5.0), doubleArrayOf(3.0), doubleArrayOf(4.0), true)[0], 2.0))
    val a = doubleArrayOf(1.0, 2.0, 3.0, 2.0); val b = doubleArrayOf(2.0, 2.0, 2.0, 2.0)
    check("crossover", Pine.crossover(a, b).toList() == listOf(false, false, true, false))
    check("crossunder", Pine.crossunder(a, b).toList() == listOf(false, false, false, false) || Pine.crossunder(a, b)[3].not())
    check("crossunder fires", Pine.crossunder(doubleArrayOf(3.0, 1.0), doubleArrayOf(2.0, 2.0))[1])
}

fun oscillatorTests() {
    // cross age: 0 on the cross bar, +1 each later bar, reset on opposite cross
    val bull = booleanArrayOf(false, true, false, false, false, false)
    val bear = booleanArrayOf(false, false, false, false, true, false)
    val cs = trackCross(bull, bear, doubleArrayOf(0.0, 10.0, 11.0, 12.0, 13.0, 14.0))
    check("cross dir", cs.dir.toList() == listOf(0, 1, 1, 1, -1, -1))
    check("cross age", cs.age.toList() == listOf(0, 0, 1, 2, 0, 1))
    check("value at cross", near(cs.valueAtCross[3], 10.0) && near(cs.valueAtCross[5], 13.0))
    // WT strength tiers (spec 8.2), boundaries inclusive
    check("wt bull triple", wtStrength(1, -53.0) == Strength.TRIPLE && wtStrength(1, -52.9) == Strength.DOUBLE)
    check("wt bull double/single", wtStrength(1, 0.0) == Strength.DOUBLE && wtStrength(1, 0.1) == Strength.SINGLE)
    check("wt bear", wtStrength(-1, 53.0) == Strength.TRIPLE && wtStrength(-1, 0.0) == Strength.DOUBLE && wtStrength(-1, -0.1) == Strength.SINGLE)
    // STCR strength tiers (spec 8.3)
    check("stcr bull", stcrStrength(1, 19.99) == Strength.TRIPLE && stcrStrength(1, 20.0) == Strength.DOUBLE &&
        stcrStrength(1, 80.0) == Strength.DOUBLE && stcrStrength(1, 80.01) == Strength.SINGLE)
    check("stcr bear", stcrStrength(-1, 80.01) == Strength.TRIPLE && stcrStrength(-1, 80.0) == Strength.DOUBLE &&
        stcrStrength(-1, 20.0) == Strength.DOUBLE && stcrStrength(-1, 19.9) == Strength.SINGLE)
    // WT timeframes: nothing below 5 minutes
    check("wt params", wtParams(1) == null && wtParams(3) == null && wtParams(5) == (9 to 12) &&
        wtParams(15) == (10 to 21) && wtParams(60) == (10 to 21) && wtParams(1440) == (12 to 24))
    // EMA trend states (spec 10.9)
    check("ema trend", emaTrendState(110.0, 105.0, 100.0) == EmaTrend.STRONG_BULL &&
        emaTrendState(104.0, 105.0, 100.0) == EmaTrend.WEAK_BULL &&
        emaTrendState(90.0, 95.0, 100.0) == EmaTrend.STRONG_BEAR &&
        emaTrendState(96.0, 95.0, 100.0) == EmaTrend.WEAK_BEAR &&
        emaTrendState(100.0, 100.0, 100.0) == EmaTrend.NEUTRAL)
    check("ema50 distance", near(distanceToEma50Pct(102.0, 100.0), 2.0))
}

fun sessionTests() {
    val nse = SessionCalendar.NSE; val mcx = SessionCalendar.MCX
    check("trading day", nse.tradingDay(istMs(2026, 9, 1, 9, 15)) == 20260901)
    // 5M -> 15M and 60M for a full NSE day (75 bars)
    val full = day(2026, 9, 1, List(75) { r(100.0, 101.0, 99.0, 100.0) })
    val m15 = aggregate(full, 5, 15, nse)
    check("nse 15m buckets", m15.candles.size == 25 && m15.incompleteBuckets.isEmpty())
    val h1 = aggregate(full, 5, 60, nse)
    check("nse 1h buckets (7, last is 15m long)", h1.candles.size == 7 && h1.incompleteBuckets.isEmpty() &&
        h1.candles[6].t == istMs(2026, 9, 1, 15, 15))
    check("daily bucket", aggregate(full, 5, 0, nse).candles.size == 1)
    // gap: remove one candle -> bucket reported incomplete, not filled
    val gap = full.filterIndexed { i, _ -> i != 14 }
    val g = aggregate(gap, 5, 60, nse)
    check("gap flagged", g.candles.size == 6 && g.incompleteBuckets.size == 1)
    // MCX 09:00-23:30
    val mday = day(2026, 9, 1, List(174) { r(100.0, 101.0, 99.0, 100.0) }, 9, 0)
    val mh = aggregate(mday, 5, 60, mcx)
    check("mcx 1h buckets (15, last 30m)", mh.candles.size == 15 && mh.incompleteBuckets.isEmpty())
    check("mcx bucket end", mcx.bucketEnd(istMs(2026, 9, 1, 23, 10), 60) == istMs(2026, 9, 1, 23, 30))
}

private fun mk(rows: List<DoubleArray>, d: Int = 1): Bars = Bars(day(2026, 9, d, rows), SessionCalendar.NSE, 5)

fun scannerScenarioTests() {
    // ---- DIR: earliest-volume tie wins; first/last candle excluded
    fun d1(): List<DoubleArray> = List(75) { i ->
        when (i) {
            0, 74 -> r(100.0, 300.0, 50.0, 100.0, 9999.0)       // must be excluded
            10 -> r(104.0, 110.0, 100.0, 105.0, 500.0)
            30 -> r(194.0, 200.0, 190.0, 195.0, 500.0)           // tie: earlier (idx10) wins
            else -> r(104.0, 105.0, 103.0, 104.0, 100.0)
        }
    }
    fun d2(first: Double): List<DoubleArray> = List(75) { i ->
        if (i == 0) r(120.0, 121.0, 119.0, first, 100.0) else if (i == 20) r(120.0, 125.0, 115.0, 120.0, 700.0) else r(120.0, 121.0, 119.0, 120.0, 100.0)
    }
    val c1 = day(2026, 9, 1, d1()); val c2 = day(2026, 9, 2, d2(150.0)); val c3 = day(2026, 9, 3, d2(118.0))
    val bars = Bars(c1 + c2 + c3, SessionCalendar.NSE, 5)
    val dir = directionDecider(bars)
    check("DIR day1 no reference", dir[0] == 0 && dir[74] == 0)
    check("DIR day2 earliest tie -> bull", dir[75] == 1 && dir[100] == 1 && dir[149] == 1)
    check("DIR day3 inside ref candle -> 0", dir[150] == 0)
    val dirBear = directionDecider(Bars(c1 + day(2026, 9, 2, d2(95.0)), SessionCalendar.NSE, 5))
    check("DIR below ref low -> bear", dirBear[75] == -1)

    // ---- IB setup A, then count
    val ib = mk(listOf(
        r(100.0, 100.0, 100.0, 100.0),
        r(100.0, 110.0, 99.0, 108.0),     // green mother
        r(107.0, 109.0, 98.0, 103.0),     // red, inside, only low wick breaks -> setup A, crosses lower line
        r(97.0, 98.5, 96.0, 97.0),        // fully below 99 -> -1 (1)
        r(97.0, 98.0, 95.0, 96.0),        // -1 (2)
        r(96.0, 100.0, 95.0, 99.5),       // high above line -> cleared
    ))
    val ibr = levelsSignal(ib, false)
    check("IB A sequence", ibr.dir.toList() == listOf(0, 0, 0, -1, -1, 0) && ibr.count.toList() == listOf(0, 0, 0, 1, 2, 0), ibr.dir.toList().toString() + ibr.count.toList())

    // ---- VM: 3 rising-volume green candles, failed continuation fixes the lines, then up-cross
    val vm = mk(listOf(
        r(100.0, 102.5, 99.5, 102.0, 100.0), r(102.0, 104.5, 101.5, 104.0, 200.0), r(104.0, 106.5, 103.5, 106.0, 300.0),
        r(106.0, 106.0, 104.5, 105.0, 100.0),   // red -> setup done, lines 106.5 / 99.5
        r(107.0, 109.0, 105.0, 108.0, 100.0),   // crosses upper line (armed)
        r(108.0, 110.0, 107.5, 109.0, 100.0),   // fully above -> up (1)
        r(109.0, 111.0, 108.0, 110.0, 100.0),   // up (2)
        r(110.0, 110.5, 106.0, 106.0, 100.0),   // touches again -> cleared, armed
        r(108.0, 110.0, 107.0, 109.0, 100.0),   // up (1) again, same direction -> RT
    ))
    val vr = levelsSignal(vm, true)
    check("VM sequence", vr.dir.toList() == listOf(0, 0, 0, 0, 0, 1, 1, 0, 1) && vr.count.toList() == listOf(0, 0, 0, 0, 0, 1, 2, 0, 1), vr.dir.toList().toString() + vr.count.toList())
    check("VM RT on repeat", vr.rt[5] == 0 && vr.rt[8] == 1)

    // ---- MP: 24 rows, POC bucket centre, then touch / break / return
    val mpDay1 = listOf(r(100.0, 101.0, 100.0, 100.5, 100.0), r(110.0, 110.5, 110.0, 110.2, 1000.0), r(110.2, 112.9, 110.2, 112.0, 400.0), r(123.5, 124.0, 123.5, 124.0, 50.0))
    val mpDay2 = listOf(
        r(112.0, 112.0, 111.0, 111.5), r(111.0, 111.0, 110.0, 110.8), r(111.5, 112.0, 111.0, 111.8),
        r(112.0, 113.0, 112.0, 112.5), r(112.0, 112.5, 110.0, 111.0), r(109.0, 109.5, 108.0, 108.5),
    )
    val mpBars = Bars(day(2026, 9, 1, mpDay1) + day(2026, 9, 2, mpDay2), SessionCalendar.NSE, 5)
    val mp = marketProfile(mpBars, 24)
    check("MP no POC on first session", mp.poc[0].isNaN())
    check("MP POC = 110.5", near(mp.poc[4], 110.5, 1e-12), mp.poc[4].toString())
    // day2 bars at idx 4..9
    check("MP untouched first bar", !mp.touchedToday[4] && mp.dir[4] == 0)
    check("MP touch", mp.touchedToday[5] && mp.dir[5] == 0)
    check("MP break up count 1,2", mp.dir[6] == 1 && mp.count[6] == 1 && mp.dir[7] == 1 && mp.count[7] == 2)
    check("MP return touch sets RT", mp.dir[8] == 0 && mp.rt[8] == 1)
    check("MP break down", mp.dir[9] == -1 && mp.count[9] == 1 && mp.rt[9] == 0)
    check("MP pct", near(mp.pctFromPoc[9], (108.5 - 110.5) / 110.5 * 100.0))

    // MP tie: two equal-volume buckets -> the LOWEST bucket wins (strict >)
    val tie = Bars(day(2026, 9, 1, listOf(r(100.0, 100.5, 100.0, 100.2, 100.0), r(123.5, 124.0, 123.0, 123.5, 100.0))) +
        day(2026, 9, 2, listOf(r(105.0, 106.0, 104.0, 105.0))), SessionCalendar.NSE, 5)
    check("MP tie -> lowest bucket", near(marketProfile(tie, 24).poc[2], 100.5, 1e-12), marketProfile(tie, 24).poc[2].toString())

    // ---- H: needs 10 completed hours; rising series -> bull
    val rise = (0 until 150).map { i -> val c = 100.0 + 0.1 * i; r(c - 0.05, c + 0.05, c - 0.05, c) }
    val hb = Bars(day(2026, 9, 1, rise.subList(0, 75)) + day(2026, 9, 2, rise.subList(75, 150)), SessionCalendar.NSE, 5)
    val h = hourlyTrend(hb)
    check("H neutral before 10 hours", h[74] == 0 && h[109] == 0)
    check("H bull after 10th hour", h[110] == 1 && h[149] == 1, "${h[110]} ${h[149]}")

    // ---- Opening values
    check("Opn", openDirection(101.0, 100.0) == 1 && openDirection(99.0, 100.0) == -1 && openDirection(100.0, 100.0) == 0 && openDirection(NA, 100.0) == null)
    check("gap", near(gapPct(102.0, 100.0), 2.0) && near(gapPct(98.0, 100.0), -2.0))
    val ob = mk(listOf(r(100.0, 101.0, 99.0, 100.5), r(100.5, 102.0, 100.0, 101.5), r(101.5, 103.0, 101.0, 102.5), r(102.5, 103.0, 102.0, 102.0)))
    val os = openingStats(ob, 0)
    check("5M range", near(os.five, (101.0 - 99.0) / 99.0 * 100.0) && os.fiveBullish == true)
    check("15M range", near(os.fifteen, (103.0 - 99.0) / 99.0 * 100.0) && os.fifteenBullish == true)
    check("15M needs 3 bars", openingStats(mk(listOf(r(100.0, 101.0, 99.0, 100.5), r(100.5, 102.0, 100.0, 101.5))), 0).fifteen.isNaN())

    // ---- Bias warm-up
    val br = (0 until 200).map { i -> val c = 100.0 + 0.2 * i; r(c, c + 0.1, c - 0.1, c) }
    val bb = Bars(day(2026, 9, 1, br.subList(0, 75)) + day(2026, 9, 2, br.subList(75, 150)) + day(2026, 9, 3, br.subList(150, 200)), SessionCalendar.NSE, 5)
    val bp = bias(bb, true, 150)
    check("Bias void before warm-up", (0 until 149).all { bp[it] == -1 })
}

fun crossCheck(dir: File) {
    val rows = File(dir, "bars.csv").readLines().map { it.split(',') }
    val candles = rows.map { Candle(it[0].toLong(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble()) }
    val b = Bars(candles, SessionCalendar.NSE, 5)
    val lines = File(dir, "ref.csv").readLines()
    val keys = lines[0].split(',')
    val ref = HashMap<String, DoubleArray>()
    keys.forEachIndexed { k, name -> ref[name] = DoubleArray(b.n) { val x = lines[it + 1].split(',')[k]; if (x.equals("nan", true)) Double.NaN else x.toDouble() } }
    fun cmp(name: String, got: DoubleArray, tol: Double = 1e-9) {
        val want = ref[name]!!
        var worst = 0.0; var bad = -1
        for (i in 0 until b.n) {
            if (!near(got[i], want[i], tol)) { bad = i; break }
            if (!got[i].isNaN()) worst = maxOf(worst, abs(got[i] - want[i]))
        }
        check("xcheck $name", bad < 0, "first mismatch at bar $bad got=${if (bad >= 0) got[bad] else 0.0} want=${if (bad >= 0) want[bad] else 0.0}")
    }
    fun cmpI(name: String, got: IntArray) = cmp(name, DoubleArray(got.size) { got[it].toDouble() }, 0.0)
    val r20 = Pine.rsi(b.c, 20); val r100 = Pine.rsi(b.c, 100)
    cmp("rsi20", r20); cmp("rsi100", r100)
    val mr = mrsi(b.c); cmpI("mrsi_dir", mr.cross.dir); cmpI("mrsi_age", mr.cross.age)
    cmp("sma14_rsi20", Pine.sma(r20, 14))
    val rs = rsiSmaDetailCross(b.c); cmpI("rsisma_dir", rs.dir); cmpI("rsisma_age", rs.age)
    val g = gmma(b.c); cmp("gmma_osc", g.osc); cmp("gmma_sig", g.sig); cmpI("gmma_dir", g.cross.dir); cmpI("gmma_age", g.cross.age)
    val w = waveTrend(b.h, b.l, b.c, 9, 12); cmp("wt1", w.wt1); cmp("wt2", w.wt2); cmpI("wt_dir", w.cross.dir); cmpI("wt_age", w.cross.age); cmp("wt_at", w.cross.valueAtCross)
    val s = stochRsi(b.c); cmp("stcr_k", s.k); cmp("stcr_d", s.d); cmpI("stcr_dir", s.cross.dir); cmpI("stcr_age", s.cross.age); cmp("stcr_at", s.cross.valueAtCross)
    val a = adxDi(b.h, b.l, b.c, 14); cmp("adx", a.adx); cmp("dip", a.diPlus); cmp("dim", a.diMinus)
    val q = squeezeMomentum(b.h, b.l, b.c); cmp("sqz_val", q.value); cmpI("sqz_on", IntArray(b.n) { if (q.sqzOn[it]) 1 else 0 })
    cmpI("bias_plus", bias(b, true)); cmpI("bias_minus", bias(b, false))
}
