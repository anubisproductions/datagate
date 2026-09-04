# Contributing

## Translations — the easiest and most useful contribution

This app is aimed at people on metered, prepaid data. That is disproportionately not an
English-speaking audience, so a good translation is worth more here than most features.

1. Copy `app/src/main/res/values/strings.xml` to
   `app/src/main/res/values-<code>/strings.xml` (`values-ar`, `values-hi`, `values-pt-rBR`…).
2. Translate the text between the tags. Leave `%1$s` placeholders exactly as they are — they
   are substituted at runtime with a size or a date.
3. Escape `'` as `\'` and `&` as `&amp;`.
4. Keep it short. These strings sit next to numbers in narrow rows; a translation twice the
   length of the English will be truncated.

RTL languages need no layout work — the layouts use `paddingStart`/`paddingEnd` and
`layout_marginStart` rather than left/right, and `android:supportsRtl="true"` is set.

**Register terms accurately.** "Background data" means data an app spent on its own, without
the user opening it. If your language has an established phrase for that (carriers usually
have one), use theirs rather than a literal translation — it is what people already recognise
from their bill.

## Code

- Kotlin, 4-space indent, no wildcard imports.
- Comments explain **why**, not what.
- **Do not add the `INTERNET` permission.** It is the app's central promise and its main
  differentiator. A change requiring it will not be merged without a discussion first, and
  that includes analytics, crash reporting and ad SDKs — none of which this app will have.
- No new dependencies without a reason. The app deliberately ships with almost none, which is
  why the APK is small and auditable.

## Reporting a bug

Include your device, Android version and OEM skin. Samsung, Xiaomi and Huawei each stop
background services differently and most "it stopped working" reports trace back to that.

For an accounting discrepancy, please include what Android's own Settings → Connections →
Data usage shows for the same app and period. Our figures should match; if they don't, that
is the most serious kind of bug this app can have and it will be prioritised.

## What will not be accepted

- Ads, analytics, telemetry, crash reporting, or anything else requiring network access
- Ad-blocking or host-file filtering — Google Play forbids apps that block ads in other apps,
  and it would get the app removed
- Anything that makes the free tier less useful in order to sell the paid one
