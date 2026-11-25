# Privacy policy

**Cuff** — Android app, package `com.srineere.cuff`.

Effective 23 November 2026.

## The short version

Cuff reads some of the most personal data a phone holds: every notification
that arrives, who is calling, and — if you switch the Health tab on — your
steps, sleep and heart rate.

**None of it leaves your phone.** Not to us, not to anyone else. There is no
analytics, no crash reporting, no advertising, and no server belonging to
this app for anything to be sent to.

**There is one exception, and it is not ours.** If you set up an Amazfit
Helio Strap, Cuff signs in to *your own Zepp account* and asks Zepp for the
list of devices on it, because that list is the only place the strap's
pairing key exists. Nothing about you and nothing you have measured goes with
that request; it carries your Zepp token and your Zepp user id and asks one
question. Once the key is stored the call is never made again, and you can
sign out and the strap still buzzes.

This is a real change in what this app is, so it is worth saying plainly what
it cost. Until the Helio Strap, Cuff did not request Android's `INTERNET`
permission at all, and that was the strongest promise in this document: a
build that tried to send your data somewhere would have been stopped by the
operating system rather than by our good intentions. That is no longer true,
and no wording can make it true again.

What is still true, and checkable:

- **The Fitbit Air half never touches the network.** Notifications, calls,
  rules, the relay, the Health tab and the widgets work with the radio off.
- **The permission lives in one module**, `:helio`, which is the only code in
  the repository that can open a socket. It declares the permission in its
  own manifest, so a build without that module is a build that physically
  cannot reach the network.
- **There is exactly one request**, to Zepp, and only after you ask for a
  Helio Strap. `helio/src/main/kotlin/org/openstrap/app/zepp/ZeppCloud.kt` is
  the whole of it.

## What the app reads, and why

**Your notifications.** Granting notification access lets Android hand Cuff
every notification your phone posts. Cuff reads the metadata it needs to
decide whether something is worth a buzz and what that buzz should feel like:
which app posted it, whether it is a call, which notification channel it came
from, and whether it is part of a conversation you have chosen to allow.
**Message text and sender names are not read, not stored, and not sent to the
band.** The band has no screen; it only vibrates.

**Who is calling.** To let you give one person their own vibration, Cuff has
to recognise that person again next time. The phone number or call handle is
hashed with SHA-256 and only the hash is kept. The raw number is never
written to storage.

**Your contacts** (optional). Used for one thing: turning a number into a name
so a row says "Priya" instead of digits. The lookup happens when a screen is
drawn and the name is not stored. Refuse this permission and the app shows
numbers.

**Your health data** (optional, off by default). The Health tab is switched
off on a fresh install. Turning it on asks Health Connect for read access to
steps, sleep, heart rate, resting heart rate, heart rate variability,
respiratory rate, skin temperature, VO2 max, exercise, distance, calories
burned, nutrition and weight — granted one at a time, and the app works with
whatever subset you allow. These are read to draw the charts, scores and
widgets in the app and for nothing else.

Two further Health Connect grants are asked for separately: reading further
back than thirty days, so a ninety-day chart is not empty, and reading while
the app is off screen, so a widget and the hourly step nudge work without the
app open.

**The one thing Cuff writes** is food you log yourself, written to Health
Connect as nutrition records so other apps can see it. Nothing else is ever
written, and food you logged before is left alone if you switch the Health
tab off.

**Your band's Bluetooth connection.** Bluetooth permissions are used to find
and talk to your band and nothing else. `BLUETOOTH_SCAN` is declared with
`neverForLocation`: the app does not derive, request or use your location.

## Where it is kept

On your phone, in the app's own private storage, which no other app can read.
That includes your relay rules, your goals, the alert history shown on the
Alerts tab, a cached copy of recent health readings so screens open with data
instead of a spinner, and a local crash log you can read and clear from the
About screen.

**There is no backup.** `allowBackup` is off and both Android's cloud backup
and its device-to-device transfer are explicitly excluded. Nothing is copied
to Google's servers for a smoother reinstall. The cost of that is real:
uninstalling the app deletes everything it held, permanently, and a new phone
starts empty.

## Who it is shared with

No analytics provider, no advertising network, no cloud sync of your
readings, and nothing sold or transferred to anyone for any purpose.

Three things leave the app only because you ask them to, and only where you
send them:

- **Export** writes your readings as CSV files to a location you pick in the
  system file picker.
- **Food logging** writes nutrition records to Health Connect, where other
  apps you have authorised can read them.
- **The Zepp sign-in**, if and only if you set up a Helio Strap. You sign in
  on Zepp's own page, in a web view; Cuff never sees your password, only the
  session cookie that page leaves behind. It then asks Zepp's device-list
  endpoint for the straps on your account. Your health data is not sent, not
  requested and not involved.

## Your Zepp sign-in, if you have one

Kept in the app's private storage: the Zepp session token, your Zepp user id,
and the strap's pairing key. No password is stored, because none is ever
seen.

It exists for one reason — the strap refuses to talk to anything that cannot
prove it holds that key, and Zepp issues the key. There is no offline way to
get one; the open-source project this transport is built from looked, and
has no local-pairing path either.

The Device screen can forget all of it, which signs you out and leaves the
strap unusable until you sign in again. Forgetting it does not touch your
Zepp account.

## Deleting it

Uninstalling the app deletes everything it stored. Within the app, the Alerts
history and the crash log can be cleared on their own, and switching the
Health tab off empties the cached readings immediately.

Food already written to Health Connect is not deleted, because at that point
it is your record in Health Connect rather than the app's — remove it there
if you want it gone.

## Children

Cuff is not directed at children and collects nothing from anyone.

## About the code

Cuff is unofficial, and is not made, endorsed or supported by Fitbit or
Google.

The source is public, at <https://github.com/srineere/cuff>, so every claim
above can be checked by reading it rather than believed. Two places are worth
starting at: `app/src/main/AndroidManifest.xml`, which is the whole list of
what the app may do, and `health/HealthWriter.kt`, which is the only code in
the repository that writes anything anywhere.

If you inspect the APK rather than the source, you will find the `INTERNET`
permission and should: this document would be lying if you did not. What the
APK cannot show you is that it is reached from one file. The source can, and
`helio/src/main/AndroidManifest.xml` says why it is there at all.

Two honest notes for anyone who does inspect the APK. The vendored Golden
Gate transport includes an OkHttp-based remote-debugging class from upstream,
so OkHttp appears among the bundled libraries; nothing in Cuff calls it. And
the Zepp request identifies itself as Zepp's own app, because Zepp's
device-list endpoint will not answer a client it does not recognise. That is
how the key is obtained at all, and it is made with your token, for your
account, asking only which straps are on it.

## Changes

If this policy changes, the new version is published here with a new
effective date, and the change is noted in the app's changelog.

## Contact

Open an issue at <https://github.com/srineere/cuff/issues>. For anything that
should not be public — a security report, or a privacy question you would
rather not ask in the open — see [`SECURITY.md`](SECURITY.md).

## Where this document lives

This file is the source of truth, at
<https://github.com/srineere/cuff/blob/native/PRIVACY.md>. Any copy elsewhere
is a mirror; if the two disagree, the version carrying the later effective
date is the current one.
