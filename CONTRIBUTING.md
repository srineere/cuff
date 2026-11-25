# Contributing

This is a small project with one maintainer and one band to test on. That
shapes everything below: the slow parts are not code review, they are
verifying that a change still works against hardware nobody has documented.

## The most useful thing you can send

**A firmware report.** The Air's transport is reverse-engineered and version
sensitive — Google Health or a band firmware update can break it without
warning, and there is no changelog for either. A report that the relay *still
works* on a build this has not seen is as valuable as a report that it broke,
and it is the one kind of issue nobody else can file for you. There is a
template for it: *Issues → New issue → Firmware report*.

After that: bug reports with the band's firmware version, your phone, your
Android version, and what the Alerts tab said when it failed. The failure
notes in that list are deliberately specific — "the band was busy with
something else" and "Google Health may have been using the band" are different
faults, and quoting the exact wording saves a round trip.

## Before you open a pull request

**Read [`docs/HANDOVER.md`](docs/HANDOVER.md).** Not as politeness — several of
the things it records as settled look like obvious improvements and are not.
The one-session rule, the ring timings, the deliberate absence of DataStore and
of saved navigation state are all there because the alternative was tried and
cost a day. [`docs/DESIGN.md`](docs/DESIGN.md) is the companion: how the thing
is built, written for somebody arriving cold.

**Run what CI runs**, because lint failures fail the build:

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest :air:testDebugUnitTest :helio:testDebugUnitTest
```

```bash
./gradlew :app:lintDebug
```

No keystore is needed for any of that. Release signing falls back to the debug
key when none is configured, deliberately, so a contributor without the key can
still build — see [`docs/SIGNING.md`](docs/SIGNING.md).

**Try it on hardware if it touches hardware.** There is no emulator for a
Fitbit Air. Anything that reaches the band has to be tried against a band, and
anything that changes a screen has to be looked at on a phone — layout faults
do not fail a build. Say in the PR what you tried it on. If you have no band
and the change needs one, say that too; it is better than a silent assumption,
and the maintainer can run it.

## Things that are easy to get wrong here

These are the ones that have actually gone wrong, which is why they are listed
rather than left to review:

- **Nothing new leaves the phone.** The app does not hold the `INTERNET`
  permission and must not acquire it. Message text and sender names are not
  read, not stored and not sent to the band; `RelayActivity.toMap()` is
  asserted to carry no label. If a change makes anything leave the device — a
  file export, a share sheet — say so in the PR and say what the person sees
  before it goes.
- **The stored config is versioned.** If `RelayRuleConfig` gains a field: bump
  the version, default it in `decode` from the old value, and add a test that
  an old config decodes to the same *behaviour*. The way a missed migration
  fails is silently, on somebody else's phone.
- **Colours come from `MaterialTheme`**, never from constants, and nothing asks
  `isSystemInDarkTheme()` to pick one — use `MaterialTheme.isDark`. Every
  neutral is mixed from whichever ground is in use; a hardcoded grey is a
  contrast bug waiting for a theme switch.
- **Times** use `DateFormat.getTimeFormat(context)`, never a format string, so
  a phone on a 12-hour clock gets a 12-hour clock.
- **Health Connect reads** are guarded by their own permission and wrapped. In
  an origin filter, `null` means "do not filter" and an empty set means
  "nothing" — an empty `dataOriginFilter` matches *every* origin, which is the
  opposite of what it looks like.
- **Glance containers hold at most ten children.** Anything with more parts
  than that has to be drawn as a bitmap.

## Commit and PR style

Commit messages here are full sentences describing the substance of a change,
with a body saying what was wrong and why the fix is shaped the way it is.
There are no conventional-commit prefixes. `git log` is the best guide; match
what you see.

The pull request template asks what changed, how it was checked, and walks the
list above. Tick what you actually did and leave the rest unticked — an honest
gap is more useful than a full set of ticks.

Update [`CHANGELOG.md`](CHANGELOG.md) under `[Unreleased]` for anything a user
would notice.

## Licensing of what you send

By opening a pull request you agree your contribution is licensed under this
repository's terms: the MIT Licence for the app, and Apache-2.0 for anything
under `air/src/main/kotlin/com/fitbit/goldengate/`, which is vendored and keeps
its upstream SPDX headers. There is no CLA.

If you are fixing something in the vendored transport, consider whether it
belongs upstream at
[ThoreKoritzius/fitbit-air](https://github.com/ThoreKoritzius/fitbit-air)
instead — that is where the protocol work lives, and a fix there helps more
people than a fix here.

## What this project is not going to do

Said plainly so nobody spends a weekend on it:

- **A Play Store listing.** This rides a version-sensitive protocol that can
  break without warning. It is sideloaded and will stay that way.
- **iOS.** Outside the EU, iOS does not let a third-party app reach the band
  this way at all. See
  [`docs/explorations/ios-support.md`](docs/explorations/ios-support.md).
- **Reading message text.** The band has no screen. There is nothing to show,
  so there is no reason to read it, so it is not read.
- **Anything that needs a server.** There isn't one, and the missing
  `INTERNET` permission is the proof.
