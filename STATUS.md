# Scan Master V1 — status after Stage B (calculation engine)

| Area | Status |
|---|---|
| Source audit | DONE (`STAGE_A_SOURCE_AUDIT.md`, `DECISIONS.md`) |
| Pine primitives (SMA, EMA, RMA, RSI, TR, ATR, stdev, linreg, stoch, cross) | IMPLEMENTED AND TESTED (internal) — NOT VERIFIED AGAINST TRADINGVIEW |
| GMMA, WT, STCR, MRSI, ADX, Squeeze, Bias+/− | IMPLEMENTED; cross-checked against an independent Python port — NOT VERIFIED AGAINST TRADINGVIEW |
| DIR, Opn, 5M, STK opening values, VM, IB, MP/POC, H | IMPLEMENTED; hand-built scenario tests only — NOT VERIFIED AGAINST TRADINGVIEW |
| MACD, Bollinger, ATR, VWAP, EMA trend (detail panel) | IMPLEMENTED (standard inputs), minimal tests |
| Session calendars + exchange-aware aggregation (NSE, MCX) | IMPLEMENTED AND TESTED; holidays not modelled |
| Open Interest, Volume panel | NOT IMPLEMENTED |
| Filters, presets, events, alerts | NOT IMPLEMENTED |
| Android UI, settings, charts | NOT IMPLEMENTED |
| Angel One adapter, CSV demo mode | NOT IMPLEMENTED |
| TradingView comparison screen | NOT IMPLEMENTED (no reference values received) |
| GitHub Actions APK build | NOT BUILT. `engine-tests.yml` exists but has not been run on GitHub. |

Last local run of the engine self-test (Kotlin 2.0.21 compiler, JDK 21, in the sandbox): 82 passed, 0 failed.
Mutation check: deliberately broken ADX / EMA / MP tie-break variants were detected by the tests.

## Local correction and Android starter (31 Oct 2026)
- Corrected Kotlin GMMA array division compilation issue (`s[i] = s[i] / lens.size.toDouble()`).
- Added minimal Compose Android starter and GitHub Actions `build-apk.yml`.
- App shows explicitly disconnected market state; **no live scanning and no fabricated data**.
- Bias logic is **kept in engine but withheld from UI** pending TradingView parity, as user permitted omitting it if unreliable.
- GitHub APK build not yet verified; engine tests should be rerun.
