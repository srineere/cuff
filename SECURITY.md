# Security

## Reporting something

Use GitHub's private vulnerability reporting: **Security → Report a
vulnerability** on <https://github.com/srineere/cuff>. That opens a thread only
you and the maintainer can see, which is the right place for anything you would
not want in a public issue.

Please do not open a public issue for a vulnerability first. Everything else —
crashes, wrong readings, a firmware that stopped working — belongs in a normal
issue, and is welcome there.

There is one maintainer and no schedule to promise. Expect an acknowledgement
within a week. If a report is valid and you want credit, say so and you will
get it in the changelog entry for the fix.

## What the attack surface actually is

Worth stating plainly, because it is unusual.

**There is no server, no sync and no telemetry**, and the app reaches the
network in exactly one place: the `:helio` module, which signs in to the
owner's Zepp account to fetch an Amazfit Helio Strap's pairing key. That
module declares the `INTERNET` permission in its own manifest and is the only
code in the repository that can open a socket. Everything else — the Fitbit
Air transport, the relay, the Health tab, the widgets — runs with the radio
off, and a build without `:helio` cannot reach the network at all.

That makes the list short but no longer empty:

- **The Zepp sign-in** (`:helio`). A web view onto Zepp's own login page, a
  session cookie read back from it, one call to a device-list endpoint, and a
  token and pairing key in private storage. Anything that leaks the cookie,
  the token or the key, sends them anywhere other than Zepp, widens that one
  call, or lets the web view navigate somewhere it should not, is in scope
  and is the first thing worth looking at. No password is ever handled by
  Cuff; if you find one being handled, that is a bug of the highest kind
  here.
- **The Bluetooth transports.** `:air` is Golden Gate over DTLS over GATT
  with CoAP on top; `:helio` is the Zepp channel with its own B-163 key
  exchange. Both stacks are vendored; a flaw in the vendoring, the key
  handling or the session lifecycle is ours.
- **The notification listener.** Cuff is granted every notification the phone
  posts. It reads metadata — package, channel, category, whether it is a call —
  and deliberately not message text or sender names. Anything that makes text
  or a name reach storage, a log, the activity history or the band is a bug of
  this kind, not a feature request.
- **Caller identity.** A phone number or call handle is hashed with SHA-256 and
  only the hash is stored. A path that writes the raw value anywhere is in
  scope.
- **Stored data.** Relay rules, goals, the alert history and the cached
  readings live in the app's own private storage. `allowBackup` is off and both
  cloud backup and device-to-device transfer are excluded; a change that lets
  any of it off the device is in scope.
- **Health Connect.** Reads are permission-guarded per record type. The one
  thing the app writes is food you logged yourself.

Out of scope, because they are not ours to fix:

- The Air's firmware, and anything the band does with what it is sent.
- Google Health, Health Connect, and whatever they write.
- The protocol being reverse-engineered at all, and the fact that a Google
  Health or firmware update can break it. That is a known, documented property
  of the project, not a vulnerability.
- Physical access to an unlocked phone.

## Releases and signing

Every release so far has been **debug-signed** — no release keystore is
configured for this repository, and the workflow says so in the notes of any
build that fell back to the debug key. Verify what you install:

```bash
keytool -printcert -jarfile cuff-vX.Y.Z.apk
```

Each release also publishes the SHA-256 of the APK in its notes. Check it
against the file you downloaded. Install only from
<https://github.com/srineere/cuff/releases>; there is no other distribution
channel, and anything claiming to be one is not this app.

[`docs/SIGNING.md`](docs/SIGNING.md) explains how a release key would be set
up, and why the keystore and its passwords are the maintainer's to hold and
must never pass through an issue, a pull request or a transcript.
