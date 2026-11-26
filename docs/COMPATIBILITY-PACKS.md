# Signed compatibility packs

The launcher can fetch reviewed, data-only compatibility information independently
of application releases. This reduces application updates when MCGL changes its
obfuscated class names or string keys. New rendering features or changed algorithms
can still require a new application build. Packs are published after review and
testing. Clients without a signed pack must pass the local checks below.

The current engine ABI is `core-17`. Delivery uses the separate `compatibility`
branch of this repository, at `v1/core-17/<official-client-sha256>.pack`.
The original client JAR is downloaded from MCGL and is never included in a pack.

Each pack has an eight-byte `MCGLPK1\n` header, a 64-byte Ed25519 signature,
and a ZIP payload. The pinned publisher public key is
`6bd7980e19d22ca3a68b0af6810fe06df4f6cd4d2f0e2d33291db276860e3c6e`.
Both Swift and Java verify the signature. Java then requires the exact engine ABI,
official input SHA-256, mapping and method-table SHA-256, and class count.
Only `manifest.txt`, `mapping.tsv` and `aliases.tsv` are allowed. Payloads cannot
contain executable updates or arbitrary paths. Compressed and expanded sizes are
bounded. Mapping bijection and existing bytecode guards remain mandatory.

The launcher caches verified packs for reuse without network access. Updates are
prepared in temporary directories before installation. If an update cannot be
prepared or fetched, the launcher offers an explicit saved-client launch only
when the recorded hashes of all required libraries and launch files still match.
An existing recognized core-16 installation can be adopted after a linkage audit.
The server may reject an older client; this fallback does not bypass that requirement.
User settings are excluded from the receipt so normal settings changes remain valid.

## Publisher workflow

1. Obtain the official manifest and verify the JAR against it. Review the mapping
   and every method alias against the supported implementation; never generate
   approval aliases solely to make a guard pass.
2. Compile `CreateCompatibilityPack.java`, `MCGLCompatibilityPack.java` and
   `MCGLBytecodeFingerprint.java` with the existing ASM dependency. Create the
   payload with `CreateCompatibilityPack CLIENT_SHA MAPPING.tsv ALIASES.tsv NEW.zip`.
3. Compile `SignCompatibilityPack.swift` with CryptoKit. Use
   `sign PRIVATE_KEY NEW.zip NEW.pack`. Keep the private key outside all repositories,
   application bundles and release archives. Back it up privately. Key rotation
   requires an application update.
4. Run `tools/verify-compatibility-pack.sh` with explicit local inputs, the rendering
   tests, and a real gameplay check. Publish only the signed pack under the matching
   ABI and client hash on the data branch. Test a clean download and cached reuse.

Signing is an authorization step, not evidence that a mapping is correct. Gameplay
testing remains necessary, particularly when upstream changes rendering behavior.

## Local fallback

The installer retains the original JAR after staging a client with a bundled
profile or authenticated pack. If a later client has no available signed pack,
the launcher can infer a staging mapping from that reference. It rejects
conflicts, checks an exact round trip, and accepts new aliases only when the
guarded methods differ by a supported XOR string key change. The regular
patches, name restoration, and linkage audits still have to succeed before
the staged update is installed. Locally generated profiles stay in the
temporary directory and never replace the trusted reference or enter the
downloaded pack cache.

The fallback needs a previously staged trusted reference. It can decline an
update with incompatible bytecode and cannot verify live server or rendering
behavior. The signed pack workflow still handles those releases.

## Independently updated adapter

Launcher builds starting at 264 can download executable Java patch tools from
`compatibility/adapters/v1/core-17/latest.adapter`. This channel uses a separate
`MCGLAD1\n` envelope. Ed25519 authenticates the entire payload, comprising a
four-byte big-endian JSON length, the JSON manifest, and the tools archive.
The manifest binds the archive SHA-256, revision, minimum launcher build,
Java version, installer protocol, engine ABI, and both packaged rendering-library
SHA-256 values. The existing publisher public key is pinned in the launcher.

The bundled bootstrap validates archive paths, entry types, required tools,
and expanded size before any downloaded class executes. Extraction and every
preparation attempt use disposable directories. The application bundle supplies
Java, ASM and the rendering libraries. Only a fully prepared and audited client
can be installed. The module is confirmed after client installation and its
receipt are complete. Failure retries the last verified tools, then bundled
tools. Failed revisions are skipped for the affected official-client hash.
Cached signatures are rechecked before offline use. The current and previous
confirmed signed archives are retained for rollback.

Build a module from a prepared application with
`bash tools/build-compatibility-adapter.sh PRIVATE_KEY APP REVISION MINIMUM_BUILD /absolute/NEW.adapter`.
Keep its immutable revision file beside `latest.adapter` on the compatibility
branch. Validate it with `CompatibilityAdapterTest.swift` and the fixture-based
`CompatibilityAdapterInstallTest.swift` before publication. This channel can
update patch code, while new graphics-library APIs still require an application
release. Module signing authorizes executable code, so publisher keys retain
the same private storage requirements as application signing tools.
