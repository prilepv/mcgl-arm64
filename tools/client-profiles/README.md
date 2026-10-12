# Reviewed official-client profiles

These are interoperability names, not redistributed client binaries. Obtain the
official JARs separately. Never commit downloaded clients or local account data.

The staging adapter accepts exactly these SHA-256 inputs:

| Profile | SHA-256 of official `mcgl.jar` |
| --- | --- |
| Original 1.7.2 input | `d014597ec18c5cda9622b2e5964eb2871e04b3e267d944bbbbb06e7f31f08786` |
| Updated client inspected 2026-10-12 | `be89b9beca87d446c67321f2d74881cc11146fc2c92703693de360729e204ede` |

The updated input's official MD5 is `1ef465d610a107259ac7e3b23df9afd3`.
`official-1ef465d6.tsv` contains a complete bijection: `C old-class new-class`,
`F/M old-owner old-member old-descriptor new-member` (tab separated). Canonical
names identify the existing 1.7.2 patch targets. Added classes and colliding new
members have explicit staging-only aliases. Both the JAR and mapping data are
hash-pinned; no heuristic matching runs on a user's machine.

The installer normalizes a freshly verified download, runs the existing strict
patches, and restores official names before applying any files. All 2568 classes
must round-trip with identical instructions, members, constants, frames and
metadata. Untouched classes are copied back byte-for-byte; all 879 original
resources remain unchanged. Reflection strings are never rewritten. The
temporary namespace is never launched or installed.

Reviewed non-renaming changes:

- Guarded rendering methods use new XOR keys for the same decoded strings.
  Exact SHA-256 method aliases in `MCGLBytecodeFingerprint` retain the original
  guards without discarding instructions or accepting arbitrary shapes.
- Chunk rebuilding now passes block metadata to render-pass selection. Keep
  that selection, the additional local and the original terrain-cache policy.
- Upstream quad sorting now uses stable `Double.compare` ordering and double
  multiplication by 0.5 after float addition. `QuadSort.sortUpdated` keeps these
  semantics, including NaNs, with bounded thread-local scratch storage.

Run `tools/verify-client-update.sh` with explicit local dependencies to test both
official inputs, exact round trips, negative cases, 5402 differential sort cases
per input, the Swift installer's real staging pipeline and member linkage. The
tests do not authenticate, open a game window, or modify the installed profile.
A successful offline test is not a gameplay PASS or permission to publish.

An unknown next official JAR fails closed with an update-required message. Review
its actual changes and update the profile and tests; never relax the checksum,
method fingerprints or duplicate/round-trip checks to make an update pass.
