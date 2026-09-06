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

### 7. Google Play Services is absent from the Blocking screen — FIXED

`AppRepository.load()` listed only apps with a launcher intent, and Play Services has none.
The protection still held — an app that is not listed cannot be toggled — but the
"your notifications keep working" reassurance was invisible on the one screen where it
matters, and the store listing claimed those apps were "clearly marked", which was untrue.

**Fixed:** protected packages are appended to the list explicitly, disabled, with the reason
on the row. Two related defects surfaced while verifying it:

- `reasonFor()` had no case for our own package, so the Data Saver row read "Calls and
  messages". It now reads "This app".
- The reason strings were hardcoded English inside a UI translated into eight languages.
  They are string resources now, localised in all nine locales.
- `com.google.android.gms.location.history` was in the protected set but has its own uid
  (10251 on the test device, against 10244 for Play Services and the Services Framework,
  which share one). Blocking it therefore cannot affect notifications, so it was being
  refused for no reason under a label claiming it delivered them. Removed from the set —
  refusing to let a privacy-minded user block Google's location history works against the
  entire pitch.

### 8. Filter spinner label truncates

"Worst background" renders as "Worst backgr..", and the block-mode selection truncates
similarly. Cosmetic; shorten the strings or widen the controls.

### 9. Play Billing pulls in `INTERNET` — MEASURED, and DECIDED: the app stays free

Tested 2026-09-07 by adding `com.android.billingclient:billing-ktx:7.1.1`, building release,
and running `aapt2 dump permissions` on the APK. The result is unambiguous:

```
+ com.android.vending.BILLING
+ android.permission.INTERNET      <-- merged in from the billing library
```

The dependency was reverted immediately; the shipping APK still has no `INTERNET`.

So the paid tier and the trust claim are mutually exclusive as things stand. "This app has no
internet permission" appears in the store listing, the privacy policy, the README and the
onboarding screen, and it is the one thing competitors cannot copy without re-architecting.
Play Billing costs it, for a tier `GO_TO_MARKET.md` values at roughly US$3,800 lifetime.

Three ways out, none free:

1. **Ship Billing and drop the claim.** Simplest, and throws away the differentiator.
2. **Separate paid unlock app.** A tiny "Data Saver Pro Key" listing; the free app detects it
   with `PackageManager` - which it can already do, it holds `QUERY_ALL_PACKAGES` - and
   verifies its signing certificate so a fake key cannot unlock it. No network on either side
   of that check, and Play still takes the payment because the key is a paid app. Costs a
   second listing and a clunkier purchase flow.
3. **Stay free.** Keeps the claim intact and earns nothing.

**Decided 2026-09-07: option 3. The app ships free in full.** No paid tier, no unlock app, no
ads. The trust claim is worth more to the studio than roughly US$3,800 spread over years, and
a second listing to work around our own architecture is complexity bought with no upside.

This closes the question. If it is ever reopened, option 2 is the only route that keeps both,
and the decision has to be made before any Pro feature is written, because it decides where
the unlock check lives.

### 11. Notification permission was never requested — FIXED

`POST_NOTIFICATIONS` was declared in the manifest but never requested at runtime. On API 33+
it starts denied, so the foreground-service notification was silently suppressed: the engine
ran with nothing in the shade, and the only clue was the system key icon.

Confirmed on the test device — `granted=false`, no notification, engine running.

That also made a sentence in the listing and the privacy policy untrue on most current
devices, since both promise an ongoing notification whenever blocking is active.

**Fixed:** requested when the engine starts, so the prompt has a visible reason. The privacy
policy now also states what happens if the user declines — blocking still works, the
notification just does not appear.

### 12. Notification counts were not pluralised — FIXED

The count was formatted as `%d apps restricted`, which is correct only in English. Arabic
rendered "1 تطبيقات" — a plural noun after 1 — which reads as broken to any native
speaker, and is exactly the machine-translation tell that costs this category its ratings.

**Fixed:** `<plurals>` with the quantity classes each locale actually defines — six for
Arabic, four for Russian, one for Turkish and Indonesian, which take a singular noun after a
numeral. Verified on device: Arabic now renders "تطبيق واحد مقيّد على الواي فاي".

### 13. Vendor push services were not protected

`Protected` guarded only Google Play Services and the Services Framework, on the assumption
that FCM carries every notification. That holds on a Pixel and not much else.

Xiaomi, OPPO, vivo, Realme and Transsion dominate India, Indonesia, Pakistan, Egypt and
Nigeria — every market this app has a listing for. Those devices run their own push service
alongside or instead of Play Services, and many apps deliver notifications only through it.
Blocking one was the same self-inflicted failure the Play Services guard exists to prevent,
and invisible to anyone testing on stock Android.

**Fixed:** nine vendor push packages added — Samsung, Xiaomi, Huawei, Honor (two), OPPO/Realme
(current and legacy), vivo and Meizu. Vendor app stores, cloud backup and account sync were
deliberately left out: they are heavy background consumers and blocking them costs the user
nothing they will miss, the same reasoning that keeps the Play Store unprotected.

**Not yet verified on hardware.** The package names come from vendor documentation, not from
a device. The test phone is a Samsung, so only `com.sec.spp.push` can be confirmed locally,
and it was disconnected when this landed. The other eight need a tester on each vendor's
hardware — worth folding into closed-test recruitment. Protection is by package name and a
wrong name is inert rather than harmful: `AppRepository` skips packages that are not
installed, so a bad entry simply never appears.

### 14. Blocking a system-uid app would have captured all of Android — FIXED

Found while verifying #13 on hardware.

The tunnel filters by **uid**, not by package. `com.android.settings` runs on uid 1000, the
shared system uid — 78 packages on the test device, including the connectivity checks,
telephony components, fused location and tethering. Settings carries a launcher icon, so it
appeared in the list as an ordinary blockable app. One tap would have pulled the entire
Android system into the tunnel, and the resulting breakage would have been almost impossible
for a user to attribute to this app.

A named list would not fix it — which system components carry a launcher entry differs by
OEM. The uid decides what actually gets captured, so the uid is what is now checked:
`Protected.isSystemUid()` treats any package whose app id falls below
`Process.FIRST_APPLICATION_UID` as protected, with the user id stripped so work profiles
resolve correctly.

**Fixed in two places, deliberately.** `AppRepository` marks such rows protected so the UI
never offers them; `BlockVpnService` re-checks before `addAllowedApplication`, because rules
live in SharedPreferences and outlive the screen that wrote them. A rule saved by an older
build would otherwise still have been enforced. The screen decides what is offered; the
service decides what is applied.

Verified on device: Settings now renders greyed, toggles disabled, reason "Part of Android
itself". The enforcement-side guard is logic-verified only — no stale rule for a protected
package existed to exercise it.
