# Cuff

**Calls and notifications on a Fitbit Air, plus a Health tab worth opening.**

[![CI](https://img.shields.io/github/actions/workflow/status/srineere/cuff/ci.yml?branch=native&label=CI&style=flat-square)](https://github.com/srineere/cuff/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-MIT%20%2B%20Apache--2.0-blue?style=flat-square)](#licence)
[![minSdk 28](https://img.shields.io/badge/minSdk-28-informational?style=flat-square)](app/build.gradle.kts)
[![targetSdk 36](https://img.shields.io/badge/targetSdk-36-informational?style=flat-square)](app/build.gradle.kts)

**Android only. Sideloaded. Unofficial.** Android 9 (API 28) and up, built
against API 36, `arm64-v8a` only. There is no Play listing and there will not
be one: this rides a reverse-engineered, version-sensitive device protocol, and
a firmware or Google Health update can break it without warning.

The Air is screenless and ships with no notification support — its motor fires
for alarms and Smart Wake and nothing else. This app drives that motor over
Bluetooth, so your phone's calls and notifications reach your wrist. The other
half reads the band's own measurements back out of Health Connect, which is
where the Air writes them.

## Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Install](#install)
- [Build from source](#build-from-source)
- [Releases](#releases)
- [How a pulse actually works](#how-a-pulse-actually-works)
- [Two things this hardware cannot do](#two-things-this-hardware-cannot-do)
- [Troubleshooting](#troubleshooting)
- [Architecture at a glance](#architecture-at-a-glance)
- [Contributing](#contributing)
- [Licence](#licence)
- [Credit](#credit)

## Screenshots

![Alerts and Apps](press/shots/1-alerts.png)

![Calls, how it buzzes, and what got through](press/shots/2-buzzes.png)

![Health, Energy, and burned against eaten](press/shots/3-health.png)

![Sleep, the food log, and Activity](press/shots/4-health-2.png)

![Widgets, every style, and a stack](press/shots/5-widgets.png)

![Device and Theme](press/shots/6-device.png)

Every phone above is a real capture from a real install at 1080 × 2412, dark
theme, Cuff's own Ink/Rose theme — not a mock-up. The figures are one person's
actual readings; the two caller names that appeared in them are blurred, and
the tool that does the blurring is [`press/tools/redact.py`](press/tools/redact.py),
so you can see exactly what was covered and why.

The plain, unframed captures are in [`docs/screenshots/`](docs/screenshots/),
with a note there on which shots the folder still wants. Two are deliberately
missing: a person's own rules, which cannot be shown without showing somebody's
name and number, and a live heart rate, which has to be started by hand and
holds the band's one session while it runs.

## Features

### The relay

- **Per-app rules.** Each app you enable gets its own vibration for
  notifications and, if it can ring, a separate one for calls.
- **Calls ring until answered.** A repeating pulse that stops when the phone
  stops ringing — answered, declined, missed or timed out.
- **Per-person rules.** Give a caller their own vibration, or block them. A
  phone number is one identity whichever app it rings from; a display name
  belongs to one app, so the same contact can hold a phone rule and a WhatsApp
  rule that differ.
- **Conversation rules** inside messaging apps, so one group chat can buzz and
  the rest can stay quiet.
- **Patterns and strength, chosen separately.** One pulse, two pulses, ring or
  silent, at either of the Air's two strengths, with a ring's pacing adjustable
  per app and per person.
- **Quiet hours, a pause, and a Stop button** in the relay's own notification.
  Calls can be let through the quiet window as a sub-option; a pause outranks
  everything.
- **Bursts are merged.** Twenty messages arriving together cost at most two
  Bluetooth sessions, with a fifteen-second gap so Google Health can still
  sync.
- **A history you can read.** Every attempt is recorded with its stage,
  latency and error code — and with no names and no message text, which is
  asserted by a test.

### The Health tab — off unless you want it

**This half is opt-in, and a fresh install starts without it.** The relay is
what the app is for; plenty of people want their band to buzz and nothing
else, and they should not be asked for thirteen health permissions to get it.
There is a switch at the top of the Device tab. Turning it on for the first
time is what asks Health Connect for anything, and turning it off again takes
the tab, its widgets and its two activity buzzes away — and deletes nothing,
including food you have already logged, which lives in Health Connect rather
than here.

An install that was already using it keeps it; the default is for new ones.

Because it reads Health Connect rather than the band, this half is not really
Air-specific: anything whose app writes there — a Pixel Watch, Garmin, Samsung,
Polar, Oura — fills most of these screens too. The Air-only parts are the
Device tab, the alerts and the live heart rate reading.

- **A grid you arrange.** Tiles in five sizes — S, M, W, L and XL — across a
  six-column row, reordered, resized, recoloured or taken off. Seventeen kinds
  exist; fifteen are offered on the tab. Each carries a trend: the newest third
  of its window against the oldest, in that tile's own units.
- **A screen behind every tile.** Today's heart rate and zones, resting heart
  rate over thirty days, sleep with a score built from your own nights rather
  than a recommended figure, a sleep bank, steps, energy in against energy out,
  workouts, HRV, breathing rate, skin temperature, weight and nutrition.
- **Readiness**, as a figure out of a hundred where fifty is *your* usual, not
  an average person's. Four parts — HRV, resting heart rate, last night and
  recent load — always shown beside it, and the figure withheld until sixty
  mornings have been measured.
- **Fitness age**, from your VO₂ max against the Cooper Institute's middle
  values. Called fitness age and never biological age, because the published
  models estimate cardiorespiratory fitness and nothing else.
- **A morning report**, sent once the night has actually been written rather
  than at a fixed hour, and a **journal** that asks before bed what you did
  that day.
- **A live reading from the band.** The one figure no Health Connect reader can
  produce, because it is a measurement you ask for rather than a number already
  written. It holds the Air's single session while it runs, so alerts queue
  behind it, and it stops itself after two minutes.
- **Every reading counted once.** Health Connect is a shared store and a read
  with no filter returns every writer, so a phone and a band both counting the
  same walk make one walk into two and the goal buzzes early. Steps, distance,
  calories, food, sleep and workouts are read from chosen sources; point
  measurements like HRV and weight deliberately are not, because filtering
  those could only ever delete data.
- **Buzzes from your own activity.** One when you cross the step goal, and an
  hourly nudge when an active hour falls short. Both go through the relay, so
  neither can reach your wrist while it is off.
- **A food log — the only thing this app writes.** Entries go to Health Connect
  as nutrition records, and deleting one there is done from the log. Saved
  foods live in Cuff, because Health Connect records what you ate and
  keeps no list of foods to pick from.
- **Export.** A zip of four CSVs — days, nights, workouts, food — written
  wherever the file picker is pointed.

### Widgets

Thirty-one designs in four families, in five cell sizes (2×1, 2×2, 4×1, 4×2,
4×4) plus a stack that pages several cards through one cell:

| Family | What it is |
| --- | --- |
| Your tiles | The Health tab's own cards, one per kind |
| The band | What no health app can show: state, charge, relaying, buzzes today |
| Drawn round | Arcs that close, and dials with the figure inside |
| Type alone | No card, straight on the wallpaper, coloured from the wallpaper's own hint |

Nothing in a widget opens a Bluetooth session. They read Health Connect on a
half-hourly WorkManager job and the band's figures from whatever the app last
stored, and the schedule turns itself off when no widget is placed. The
catalogue under **Device › Widgets** draws every design from the same code the
widget itself uses, so a picture there cannot go out of date.

## Install

There is no Play listing. Installing means sideloading an APK, and the app then
needs four grants that no installer can make for you.

**1. Pair the Air in Google Health first.** This app does not own the pairing
and cannot create one. Leave it paired; Cuff borrows the connection
rather than replacing it.

**2. Allow installing from your browser or file manager.** On Android 9–11 this
is *Settings → Security → Unknown sources*; on Android 12 and up it is
per-app — *Settings → Apps → Special app access → Install unknown apps* — and
you grant it to whatever app is handing over the APK.

**3. Install the APK** from [Releases](https://github.com/srineere/cuff/releases).
It targets `arm64-v8a`, which is every phone from about 2017 onward.

**4. Grant Nearby devices while the app is on screen.** The relay needs
`BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN`, and a background service cannot ask
for permissions — so open the app and grant it under
**Device → Permissions**, which is where every grant below is offered too. On
Android 11 and below this is the legacy Bluetooth and location pair instead,
which the manifest caps at API 30.

**5. Grant notification access.** Device → Permissions offers it, or do it by
hand at *Settings → Notifications → Device & app notifications*. This is the
permission that lets the app see notifications at all; without it there is
nothing to relay.

> **If the switch is greyed out, that is Android, not a bug.** From Android 13
> onwards, notification access is a *restricted setting*, and it is blocked for
> any app installed from a file rather than from a store — which is every copy
> of this one. Tapping the switch says "Restricted setting" and nothing more.
> To get through: *Settings → Apps → Cuff → ⋮ (top right) → Allow restricted
> settings*, then grant notification access as above. Reported on a Pixel,
> where the switch simply would not move.

**6. Allow it to keep running in the background**, from the same screen. On
some phones this matters more than anything in the code: without it Android
stops background work once the screen has been off a while.

**7. Only if you want the Health tab: turn it on in Device, then install
Health Connect and allow Cuff in it.** The switch is the first row on the
Device tab and it is off to begin with — the relay works fully without any of
this, and nothing below is asked for until you turn it on.
On Android 14 and up Health Connect is part of the system. On Android 13 and
below it is a separate app from the Play Store, and the Health tab will say so
rather than showing an empty grid. Cuff asks for thirteen read
permissions and one write — nutrition, which is what logging food saves — plus
two optional extras: reading further back than thirty days, and reading while
the app is off screen for the goal buzz and the widgets. Refusing either costs
a feature, not the tab.

**8. Pick your apps under Alerts › Apps**, set each one's rows, and press
Start.

Contacts are optional and asked for only when you open a caller list. They are
read to show names, and a contact's number is turned into the same one-way hash
a call arrives with — the app never stores a phone number.

## Build from source

Needs an Android SDK and a JDK 17 or newer. Gradle comes with the wrapper and
the transport is vendored, so there is nothing else to fetch and no NDK step.

```bash
./gradlew :app:assembleDebug --console=plain
./gradlew :app:testDebugUnitTest :air:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
```

Those three are exactly what [CI](.github/workflows/ci.yml) runs on every push
and pull request. Lint is not optional there: `minSdk` is 28 and the app is
built against 36, and the only API-level portability bug found so far was found
by lint rather than by use.

Install a debug build with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug installs as `com.srineere.cuff.dev`, beside a release build rather
than over it. Do not run both at once — the band grants one secure session at a
time and two builds fighting over it turns a two-second read into fifteen.

Three modules. `:app` is the app, `:air` is the Air's transport, and
`:baselineprofile` is a macrobenchmark module that records the startup profile.
`:air` carries prebuilt `libgg.so` and `libxp.so` for three ABIs rather than
building them, and the APK packs only `arm64-v8a`, the one anybody has
verified.

Regenerating the baseline profile needs a phone plugged in, and is only worth
doing when the startup path changes shape:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Release builds are signed from `key.properties` — gitignored, never
committed — or from the `CUFF_*` environment variables. **Without either
they fall back to the debug key** and must not be distributed.

## Releases

Tagged `v<versionName>`, matching `versionName` in
[`app/build.gradle.kts`](app/build.gradle.kts), currently **0.3.0** at
`versionCode` 24. The scheme is semantic in shape and pre-1.0 in meaning: the
minor number moves when a tab, a screen or the stored config's schema changes,
the patch number for fixes, and nothing is promised to be stable until 1.0.
`versionCode` increments monotonically and is never reused.

`applicationId` is `com.srineere.cuff`. It changed once, when the app was
renamed from Air Bridge, and that was a deliberate break rather than an
upgrade: a new id installs *beside* the old app rather than over it, so
anybody carrying the earlier build keeps it until they remove it themselves.
It does not change again — a GitHub release and any future listing have to
install over each other.

Pushing a tag that matches `v*` runs
[`.github/workflows/release.yml`](.github/workflows/release.yml): it assembles
the release APK, attaches it to a GitHub Release and lets GitHub generate the
change notes from the commits.

**About signing, honestly.** No signing config is checked in — `key.properties`
is gitignored and no keystore exists in this repository. The workflow therefore
signs the APK **only if** repository secrets have been set for it, namely
`CUFF_KEYSTORE_BASE64`, `CUFF_KEYSTORE_PASSWORD`,
`CUFF_KEY_ALIAS` and `CUFF_KEY_PASSWORD`. **None of those is
currently configured**, so today's releases are debug-signed: installable, and
fine for trying the app, but each release is signed with a key generated on the
CI runner, so Android will refuse to install one over another and you have to
uninstall first. The workflow says which of the two happened in the release
notes rather than leaving you to find out at install time.

[All releases →](https://github.com/srineere/cuff/releases)

## How a pulse actually works

The Air exposes no "vibrate" command. It has a vibration-*strength* setting,
and it buzzes once when that setting changes. Everything here is built on that
one side effect.

A single pulse therefore costs a secure session: connect, handshake, write,
verify. A ring instead opens that session **once and holds it**, so pulses land
**97–175 ms** apart rather than seconds. Three consequences worth expecting:

- **A call's first buzz is 2.5–3.7 s late.** That is the handshake, and it
  cannot be removed. Pulses after it are fast.
- **Patterns are counts and cadence, not waveforms.** "Two pulses" is two
  settings writes, not a rhythm.
- **Ringing delays Google Health syncs**, because it holds the tracker channel.
  Calls are short; messages leave a cooldown between sessions.

## Two things this hardware cannot do

**The status light cannot be driven.** Tested, not assumed: it has no CoAP
resource (light-shaped paths answer `4.04`, while resources that exist behind
Google Health's credentials answer `4.01`), and the device exposes exactly two
settings within a one-byte selector — haptics, and one that does nothing
visible when toggled. Colour-coding alerts by LED is not possible.

**Wrist taps cannot be detected.** The Air handles them in firmware and sends
nothing to the phone; upstream measured zero traffic from double-taps,
including taps that dismissed a real Fitbit alarm. Tap-to-silence is
impossible, not unimplemented.

## Troubleshooting

Every entry here has actually happened and been diagnosed on a device. The
longer versions are in [`docs/HANDOVER.md`](docs/HANDOVER.md).

**Nothing reaches the wrist at all.** Check in this order: notification access
granted, at least one app selected, the relay started, the band paired in
Google Health, and Nearby devices granted. The relay's own notification names
the reason when it refuses to start.

**The first buzz of a call is seconds late.** Expected. That is the DTLS
handshake and it cannot be removed; the pulses after it are fast.

**A buzz says "completed" and I felt nothing.** "Completed" means the settings
write verified, not that you felt it. The band was reached; the motor is the
part nobody can confirm from software.

**Buzzes stop after the screen has been off a while.** The app is not exempt
from battery optimisation. Allow it to keep running in the background. On some
OEM skins this needs doing in the manufacturer's own battery screen as well as
Android's.

**Google Health stopped syncing.** The band grants one secure session at a
time, and a ring holds it. Calls are short and messages leave a fifteen-second
cooldown between sessions, so this should clear on its own — but if a second
build of this app is installed, uninstall it. Two builds fighting over the band
turns a two-second read into fifteen and logs `resetting DTLS` and
`GattlinkSessionResent`.

**Buzz the band does nothing while a sync is running.** The band answers one
connection at a time, so a buzz asked for mid-sync comes back `BUSY` rather
than queueing. The button is disabled while a sync is in flight for exactly
this reason.

**Live heart rate times out.** Turn **Share heart rate** on in Google Health.
With it off the subscription write still succeeds and no readings ever arrive;
the screen gives up at ten seconds and names the toggle.

**Blood oxygen is always empty.** Fitbit is not allowed to share it.
`dumpsys package com.fitbit.FitbitMobile` shows
`WRITE_OXYGEN_SATURATION: granted=false` while skin temperature and respiratory
rate are `granted=true`. Seeing SpO₂ inside the Fitbit app says nothing — that
app reads it off the band directly. There is no blood oxygen tile for this
reason.

**Distance and pace are blank on workouts.** `READ_DISTANCE` has not been
granted. A permission nobody granted and a metric nobody wrote look identical
on screen, so the Activity rows say "distance not allowed yet" and offer the
permission sheet rather than printing a dash.

**My step count is higher here than on the band.** It was, before the source
filter: your phone writes its own steps to Health Connect under a synthetic
package name, and added to the band's they count one walk twice — about twenty
per cent on the phone this was measured on. Goals names every writer it found
and lets you choose. The default is every writer but the phone.

**My step goal fires early.** Same cause. Check **Goals** and see which sources
are counted.

**"Last night" shows the wrong night.** It should not any more. A sleep session
is filed under the day it *ended*, so at two in the morning the newest night is
the one before; the app now shows last night only when it actually ended today,
and says "measuring overnight" rather than yesterday's figures before 11:00.

**Times print in the wrong format.** They should not — the app uses
`DateFormat.getTimeFormat(context)` throughout. A hardcoded `h:mm a` once
printed "10:24 pm" onto a 24-hour phone, which is the bug that rule exists to
prevent. Report it if you see it.

**A widget shows "an error occurred when loading widget", or does not appear in
the picker at all.** Both are known launcher-side failure modes — a stack
layout containing a view type RemoteViews does not allow, and a provider the
launcher will not list. Please open an issue with your launcher's name and
version.

**After a crash, there is something to send.** The app keeps a local crash log
in its own private storage, automatically. Nothing leaves the phone until you
press the button, which hands the text to Android's share sheet so you pick the
destination and see what you are sending.

## Architecture at a glance

Three modules: `:app` (the UI, the relay, the Health layer and the widgets),
`:air` (the vendored Golden Gate transport, with prebuilt native libraries) and
`:baselineprofile` (startup profile generation). Kotlin 2.2.20, AGP 8.11.1,
Jetpack Compose with Material 3, Navigation Compose, WorkManager, Glance, and
`health-connect:connect-client:1.1.0`. No ViewModels — state is hoisted into
two long-lived objects provided through composition locals.

A notification arrives at `CalendarNotificationListener`, is classified and
filtered, matched against the person → app → default rule hierarchy, checked
for quiet hours and pause, coalesced, and handed to `CalendarRelayService`'s
single band queue, which writes to the Air's `/settings` resource over
CoAP → DTLS → Gattlink → GATT. The Health tab reads Health Connect through one
`HealthReader` into one `HealthSnapshot`, which the tab and the widgets share.

**[docs/DESIGN.md](docs/DESIGN.md)** is the full technical design: every hop of
the relay path with a diagram, the Health Connect read path including
deduplication and the day boundary, the formulae behind every derived metric,
the widget model and its binder constraints, background work, persistence and
migrations, threading, testing, and the privacy posture.

The other documents:

- [`docs/HANDOVER.md`](docs/HANDOVER.md) — the living record of decisions,
  invariants and traps that have already cost time. Read before changing
  anything.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — the features chosen on 5 November 2026
  and the reasoning behind each, including the reversal of the
  no-composite-scores rule.
- [`PRIVACY.md`](PRIVACY.md) — what the app reads, where it keeps it, and why
  none of it can leave the phone. Written to serve as the privacy policy a
  store listing requires.
- [`docs/SIGNING.md`](docs/SIGNING.md) — how to sign releases with a real key
  instead of the debug one, and why every release so far has made people
  uninstall the last.
- [`docs/REVIEW.md`](docs/REVIEW.md) — an earlier consistency and performance
  review.
- [`docs/explorations/`](docs/explorations/) — the research the roadmap came
  out of: feature gaps against other trackers, food data sources, other
  wearables' data, and why iOS support is not possible outside the EU.
- [`CHANGELOG.md`](CHANGELOG.md) — what has changed.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to send something, what is easy to
  get wrong here, and what this project is not going to do.
- [`SECURITY.md`](SECURITY.md) — where to report privately, and what the attack
  surface is, and where the one network call lives.
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — Contributor Covenant 2.1.
- [`press/`](press/) — the store-style cards at the top of this file, the
  captures they are built from, and the scripts that build them.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) is the full version, including what this
project is deliberately not going to do. The summary:

Issues are welcome, particularly the two the templates exist for: something not
working, and a firmware version this has not been tested against. Firmware
reports are genuinely useful — the protocol is undocumented and version
sensitive, and a report that it still works on a new build is as valuable as a
report that it broke.

If you are opening a pull request:

- Read [`docs/HANDOVER.md`](docs/HANDOVER.md) first. Several of the things it
  records as settled look like obvious improvements and are not.
- Run the three CI commands locally before pushing. Lint failures fail the
  build.
- Match the existing commit style. Messages here are full sentences describing
  the substance of a change, with a body explaining what was wrong and why the
  fix is shaped the way it is. There are no conventional-commit prefixes.
- Changes to the stored relay config need a version bump, a migration and a
  test that an old config decodes to the same *behaviour*. The way such a
  migration fails is silently.
- Anything that touches the band has to be tried on a band. There is no
  emulator for this.

Security reports go through GitHub's private vulnerability reporting rather
than a public issue — [`SECURITY.md`](SECURITY.md) says what is in scope and
what is not. [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) applies to everything
here.

## Licence

Two licences, for two parts of the repository.

- [`LICENSE`](LICENSE) is the **MIT Licence** and covers this repository. It
  carries two copyright lines: Thore Koritzius', which came with the upstream
  project this app began inside and had to stay, and Srineer Kaleri's for the
  work added on top of it.
- [`LICENSE-APACHE-2.0`](LICENSE-APACHE-2.0) is the **Apache License 2.0**,
  which covers the vendored Golden Gate transport — the Kotlin bindings under
  `air/src/main/kotlin/com/fitbit/goldengate/`, which keep their SPDX headers,
  and the prebuilt native libraries built from that source.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) lists every third-party
  component and its licence, including Mbed TLS 2.28.10 and lwIP, both
  statically linked into `libgg.so`. Upstream's own `NOTICE` is reproduced at
  [`third_party/golden-gate/NOTICE`](third_party/golden-gate/NOTICE).

No binary from the Google Health or Fitbit Android applications is included in
this repository or in any build of it, and none is loaded at runtime.

## Credit

Built on [ThoreKoritzius/fitbit-air](https://github.com/ThoreKoritzius/fitbit-air)
(MIT) — an independent reverse-engineering of the Air's Golden Gate / DTLS /
CoAP transport, and the reason any of this is possible. This app began as that
project's example relay and has diverged into a product. The transport now
lives here as the `:air` module rather than as a package dependency, which is
what the rewrite from Flutter to native Android cost.

The held-session ring and the settings/resource probes developed here are
offered back upstream in
[#15](https://github.com/ThoreKoritzius/fitbit-air/pull/15) and
[#16](https://github.com/ThoreKoritzius/fitbit-air/pull/16).

Golden Gate is Fitbit's own open-source transport stack, Apache-2.0. Health
Connect, Jetpack Compose, Glance and WorkManager are Google's. Neither Fitbit
nor Google has anything to do with this app.
