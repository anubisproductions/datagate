# Known Issues

Updated 2026-09-04.

---

## Fixed

### ~~1. UI reported "Restricted" while the engine was not running~~ — fixed 2026-09-03

Rows showed `Restricted` with a green switch as soon as a rule was saved, regardless of
whether the tunnel existed. Observed live with four apps showing as restricted while
`ip addr` reported no `tun0`.

Row state is now a function of the rule set **and** `BlockVpnService.isRunning`. When rules
exist but the engine is down, rows read "Restricted — not active yet", the header reads
"Rules set, but not active — turn on to enforce", and the savings figure shows a dash rather
than a number derived from a period when nothing was enforced.

### ~~2. UI did not notice when the engine started~~ — fixed 2026-09-04

The mirror of issue 1, found while capturing screenshots. The service came back via
`START_STICKY` *after* the Activity had rendered, so the screen said "not active" while the
tunnel was genuinely up.

`BlockVpnService.isRunning` now has a setter that fires `onStateChange`, and MainActivity
subscribes in `onResume` / clears in `onPause`, re-rendering the header and rows. The UI no
longer samples engine state only when it happens to be drawing.

### ~~3. Estimated savings were permanently zero~~ — fixed 2026-09-04

`estimatedSaved` computes `dailyAverageBefore × daysBlocked − actualSince`, but callers
passed the app's **whole-cycle total** as `actualSince`. Facebook: ~36 MB expected against a
234 MB cycle total, clamped to zero. The figure could never be anything but 0 B.

Added `UsageRepository.bytesSince(pkg, since)`, which queries one package over an arbitrary
window, and `Budget.estimatedSavedFor(pkg)`, which measures actual usage since that app's own
block timestamp. Verified against the recorded baselines: Play Store 180.99 MB/day over ~11 h
→ 82.7 MB, Facebook 95.05 MB/day → 44.2 MB, total 131.1 MB. Consistent.

### ~~4. Per-network rules never applied~~ — fixed 2026-09-04

`registerNetworkCallback` was refused — `ACCESS_NETWORK_STATE` was not declared — so the
transport was never detected and every rule fell back to the mobile set. The failure was
logged but silent to the user.

Permission added (a normal, auto-granted permission that reports connectivity state and
grants no network access; `INTERNET` remains absent). Verified end to end: 3 UIDs in the
tunnel on Wi-Fi, `NET-CHANGE WIFI -> MOBILE`, 4 UIDs on mobile, matching the rule sets
exactly.

---

## Open

### 5. Savings accrue across periods when the engine was down

Partially addressed: the figure is now hidden while the engine is off. But the calculation
still assumes continuous enforcement from the block timestamp, so a period where the engine
was stopped is still counted as saved.

**Fix:** record engine up/down transitions and count only the enforced time. Needed before
the savings number is used in marketing, because it is currently an over-estimate whenever
the engine has been interrupted.

### 6. VPN consent cannot be granted through synthetic input

`adb shell input tap` raises `com.android.vpndialogs` and it is dismissed within a second —
logcat shows `noteStopComponent(... com.android.vpndialogs)` immediately after the synthetic
tap, and the app receives `RESULT_CANCELED`.

Android requires a trusted gesture for VPN consent. **A real finger is required**; this
cannot be automated and any test harness must account for it. Not an app bug, but it blocks
unattended end-to-end testing.

### 7. Google Play Services is absent from the Blocking screen

`AppRepository.load()` lists only apps with a launcher intent, and Play Services has none.
The protection still holds — an app that is not listed cannot be toggled — but the
"your notifications keep working" reassurance is invisible on that screen, and it is one of
the app's better trust signals.

**Fix:** show protected apps in the Blocking list as a disabled, clearly-labelled group even
when they have no launcher entry.

### 8. Filter spinner label truncates

"Worst background" renders as "Worst backgr..", and the block-mode selection truncates
similarly. Cosmetic; shorten the strings or widen the controls.

### 9. Play Billing may pull in `INTERNET`

Not yet verified. Before committing to the paid tier, integrate Play Billing and run
`aapt dump permissions` on the resulting APK. If the library adds `INTERNET`, the trust
claim and the "no data collected" data-safety declaration both change, and that is a
product decision rather than a detail.
