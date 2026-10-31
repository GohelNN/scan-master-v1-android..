# Decisions recorded from the user (31 Oct 2026)

| # | Decision | Effect on the project |
|---|---|---|
| 1 | SR column: **removed from the project**, no plotting | Scanner has **12 columns**: STK, DIR, Opn, 5M, VM, IB, MP, H, Bias+, Bias−, TRIGGER, ADX. The original 13-column rule is superseded by this decision. `SR.txt` is used only for the IB definition (mother-bar setups A/B). GGRR/RRGG, daily S/R and the volume mark-up drawings are not implemented. |
| 2 | MCX session **09:00–23:30 IST**, same calendar day | `SessionCalendar.MCX`. Hourly buckets start :00, last bucket 23:00–23:30 (30 min). |
| 3 | **4H removed** everywhere | ADX timeframes: 5m, 15m, 1H, Daily. No 4H aggregation. |
| 4 | Keep RT flags (VM/IB/MP), row shading for signals within n candles, MP % display | Implemented in the engine (RT) / pending in the UI (shading, MP %). |
| 5 | **No WaveTrend below 5 minutes** | `wtParams()` returns null for 1m/3m. WT shows "n/a" for those trigger timeframes. |

# Assumptions NOT yet confirmed against TradingView
* `ta.stoch` with zero range (high == low over the window) is treated as na.
* `tr` (plain built-in) is na on the first bar; `ta.tr(true)` (ADX/ATR) is high−low on the first bar.
* "Three-completed-bar EMA slope" = EMA[t] − EMA[t−3]. Spec wording is ambiguous.
* Previous-session close for Opn / G should come from the broker's previous-close field (NSE's official close can differ from the last 5M candle). The engine takes it as an input.
* Bias depends on the amount of history supplied (state has no daily reset); the source used up to 1500 5M bars.
