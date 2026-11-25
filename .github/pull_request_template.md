## What this changes

<!--
What was wrong, and why the fix is shaped the way it is. The commit messages
in this repository are full sentences describing the substance of a change,
not conventional-commit prefixes — write the same way here.
-->

## How it was checked

<!-- Tick what applies, and say what was actually run or looked at. -->

- [ ] `./gradlew :app:assembleDebug`
- [ ] `./gradlew :app:testDebugUnitTest :air:testDebugUnitTest :helio:testDebugUnitTest`
- [ ] `./gradlew :app:lintDebug`
- [ ] Tried on a phone — say which, and which Android version
- [ ] Tried with a band paired — say which firmware

Anything that touches the band has to be tried on a band; there is no emulator
for this. Anything that changes a screen has to be looked at on a phone, since
layout faults do not fail a build.

## Checks that are easy to miss

- [ ] **No new names or message text** reach the activity history or any log.
      `RelayActivity.toMap()` is asserted to carry no label.
- [ ] **The stored config.** If `RelayRuleConfig` gained a field: the version
      is bumped, `decode` defaults it from the old value, and there is a test
      that an old config decodes to the same *behaviour*. The "a newer schema
      is rejected" test has moved up with the version.
- [ ] **Health Connect reads** are guarded by their own permission and wrapped,
      and any new origin filter distinguishes `null` ("do not filter") from an
      empty set ("nothing") — an empty `dataOriginFilter` means *every* origin.
- [ ] **Colours** come from `MaterialTheme`, not from constants, and nothing
      asks `isSystemInDarkTheme()` to choose one; use `MaterialTheme.isDark`.
- [ ] **Times** are formatted with `DateFormat.getTimeFormat(context)`, never
      a format string.
- [ ] **Glance containers** hold at most ten children, and anything with more
      parts than that is a bitmap.
- [ ] **Nothing new leaves the phone.** If it does, say so here and say what
      the person sees before it goes.

## Documents

- [ ] `docs/HANDOVER.md` updated, if this changes something it records as
      settled or adds a trap worth not repeating.
- [ ] `docs/DESIGN.md` updated, if this changes the shape of a layer rather
      than the behaviour inside one.
- [ ] `CHANGELOG.md` updated under `[Unreleased]`.
- [ ] `README.md` updated, if this changes what the app does or how it is
      installed.
