# Third-party notices

No binary from the Google Health or Fitbit Android applications is included in
this repository, in a release APK, or loaded at runtime.

## Golden Gate

- Source: https://github.com/Google-Health-API/golden-gate
- Commit: `b8a6f2552e12aaf92c1549dc88a41c9b7e73f1aa`
- Copyright 2017–2020 Fitbit, Inc.
- License: Apache License 2.0

The full license text is included in [`LICENSE-APACHE-2.0`](LICENSE-APACHE-2.0).

The native libraries under [`air/src/main/jniLibs/`](air/src/main/jniLibs) are
built from this source without modification and vendored as binaries; the
build script that produced them is upstream's, not in this repository.
Upstream's `NOTICE` is reproduced in
[`third_party/golden-gate/NOTICE`](third_party/golden-gate/NOTICE).

The Kotlin sources under
`air/src/main/kotlin/com/fitbit/goldengate/bindings/` are derived from the
upstream Android bindings and retain their SPDX headers. They include limited
adaptations: a per-stack TLS key resolver and logging cleanups.

## OpenStrap / unstrap (the Amazfit Helio Strap protocol)

- Source: https://github.com/sayed0am/unstrap
- Commit: `0d323c9bfb74f5374e49f90013888fb0e57fdc57`, 23 November 2026
- Copyright 2026 OpenStrap contributors
- License: dual MIT **OR** Apache-2.0; used here under **MIT**, which is the
  licence the rest of this repository uses. Both texts are checked in, at
  [`third_party/unstrap/LICENSE-MIT`](third_party/unstrap/LICENSE-MIT) and
  [`third_party/unstrap/LICENSE-APACHE`](third_party/unstrap/LICENSE-APACHE).

The Kotlin sources under [`helio/src/`](helio/src) are a verbatim copy of the
buzz-relevant subset of that project's `protocol` module — the Zepp channel,
its authentication and B-163 key exchange, the chunk framing, the service
registry, device features and Find Device. They keep their upstream package,
`org.openstrap.protocol`, for the same reason the Golden Gate bindings keep
theirs: a vendored copy that has been renamed can no longer be diffed against
the project it came from. Every file carries a header naming the commit above,
and each is unmodified apart from that header.

The subset is deliberate. The upstream module is about four times this size;
history fetch, firmware update, alarms, settings, sport sessions and readiness
are not copied, because Cuff reads the strap's measurements from Health
Connect rather than from the strap. The upstream tests covering what *is*
copied came with it and run in CI.

Nothing from the Zepp or Amazfit Android applications is included in this
repository, in a release APK, or loaded at runtime.

## Mbed TLS (statically linked into `libgg.so`)

- Source: https://github.com/Mbed-TLS/mbedtls, release 2.28.10
  (`2fc8413bfcb51354c8e679141b17b3f1a5942561`)
- License: dual Apache-2.0 OR GPL-2.0-or-later; used here under Apache-2.0,
  whose full text is in [`LICENSE-APACHE-2.0`](LICENSE-APACHE-2.0).

## lwIP (statically linked into `libgg.so`)

- Source: https://savannah.nongnu.org/projects/lwip/ (Golden Gate submodule pin)
- Copyright (c) 2001, 2002 Swedish Institute of Computer Science
- License: BSD 3-Clause, reproduced verbatim from upstream in
  [`third_party/lwip/COPYING`](third_party/lwip/COPYING).

Clause 2 is why that file is checked in rather than linked. It requires a
binary redistribution to reproduce the copyright notice and the licence text,
and a release APK is a binary redistribution: the sources are not in this
repository, but the compiled result is inside `libgg.so`, and the obligation
travels with the binary rather than with the source. Apache-2.0 §4 puts the
same requirement on Mbed TLS, which is why its text is checked in too.

Anything that ships these `.so` files — a release APK included — carries both
notices with them.
