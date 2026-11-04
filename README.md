# MCGL ARM64 compatibility data

This branch delivers signed, data-only compatibility packs independently of app releases.
It contains no official game JARs, private signing keys, or application binaries.

`core-17` is the engine ABI in the next test build. Existing 1.7.4 applications do
not read this branch. A public application release requires gameplay acceptance.

The launcher requires an Ed25519 signature under the pinned public key
`6bd7980e19d22ca3a68b0af6810fe06df4f6cd4d2f0e2d33291db276860e3c6e`.
It verifies engine ABI, exact official client SHA-256, mapping hashes and bytecode
guards. Packs contain only manifest, name mapping and exact reviewed method hashes.
They cannot update renderer code. Major MCGL changes can still require a new app.

Current test fixture: official client SHA-256
`368b95fca25cacb9a6bfa00af6ed5bc441410c332f3159cd60ba28a0ea3d726c`,
2,593 classes, independently reviewed on 2026-11-04.

Source project and application releases: https://github.com/prilepv/mcgl-arm64
