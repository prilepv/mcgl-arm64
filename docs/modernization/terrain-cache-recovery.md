# Terrain cache allocation and recovery — 1.6.19 build 186

User-requested fix after the FPS regression reported during build 185 testing.
The accepted build 184 world algorithm, local transparency, quality settings and
build 185 native macOS Spaces implementation remain in place. This is a separate
local test APP, not a published release, and does not start milestones 11–13.
The user subsequently confirmed that FPS returned and the client worked well.
The same renderer is included in public version 1.7.0; its release also adds an
offline changelog tab. This is scene-specific acceptance, not a universal FPS claim.

## Reproduced defect

Both signed builds 184 and 185 reproduce the same storage defect. A three-vertex
triangle owns an 84 MiB page for the original 32-byte layout, while quads use
100 MiB pages for their 40-byte layout (including the arena tag and index space).
After the first quad page fills, the remaining 72 MiB of the 256 MiB arena budget
cannot fit another full quad page. Further meshes remain standalone. Freeing
space does not move these existing meshes back; their original chunk must rebuild.

An account-free fixture draws the same 1,048,576 selected vertices in the same
order. The ordinary layout needs 64 multi-draw submissions for 2,048 parts;
introducing that tiny triangle makes both old builds use 2,048 individual calls.
Fullscreen entry/exit does not change these counts. Rebuilding the unchanged
selected sources after freeing the rare layout restores batching. Pixel hashes
are identical throughout. This establishes the cache defect, not that every
millisecond of the reported game regression was caused by it.

## Changes and limits

- Terrain-only storage normalizes the exact original 32-byte vertex layout to
  the existing 40-byte quad storage layout. The first 32 bytes and original
  index data are unchanged. Added flat-input slots do not enable their shader
  flags on triangles/fans. Existing quad input requires no normalization copy.
- Full pages retain their world-sized capacity. If only a remainder is available,
  a proportionally smaller final page may use it, within the unchanged 256 MiB
  arena limit. Live pages are never resized or repacked.
- Small immutable standalone terrain sources can return to shared storage when
  pooled meshes release space. Recovery reserves a destination before readback;
  an arena that cannot fit a source performs no speculative geometry copy.
- Recovery examines at most eight sources per completed frame and reads at most
  4 MiB of original source vertex/index bytes. Upload includes the arena tag and
  32-bit indices, so this is a source-byte limit, not total bus traffic. Temporary
  CPU buffers are discarded; there is no persistent CPU shadow of the world.
  The arena budget likewise covers pooled storage, not standalone fallback meshes
  or temporary transfer buffers.
- A bounded sweep starts only after space is released. Unchanged full storage
  is not polled repeatedly. Source meshes larger than 4 MiB, unsupported layouts
  or meshes above page capacity remain standalone until their original rebuild;
  arbitrary huge sources are not read back in a frame.
- Promotion preserves the existing draw object, original primitive, attribute
  flags, matrices, order and chunk identity. The old standalone mesh closes only
  after successful import. Unload, abort and context teardown remove waiting work.

Recovery uses existing Core readback/upload operations because the tested Apple
driver does not implement the otherwise available buffer-to-buffer copy path.
It is a bounded resource-recovery operation, not an ordinary per-draw or
camera/sorting operation. It may still cost time on recovery frames; no universal
frame-time guarantee is claimed.

The installer marker becomes `lwjgl3-game-original-core-5`, ensuring that an
already-current original file manifest does not skip the changed renderer overlay.
The game remains on the `original-cache` chunk and `ordered-glyphs-v1` text policies.
Native fullscreen, its game-state hooks, GLFW patches and platform sources are
unchanged from build 185.

## Verification

`tools/verify-game-renderer.sh` includes 235,008 CPU storage assertions over original
primitive modes, packed fields and index identity. Its recovery fixture fills a
small arena, creates fallback sources, frees space and restores 20 sources over
three frames (8 + 8 + 4), without rebuilding the original chunks. Every stage checks
exact RGBA and depth, source ownership, unchanged draw identities, queue cleanup,
same-frame limits and zero repeated transfers in steady state.

The prepared APP passes 1,356 game-renderer GPU checks and 486 Core geometry checks over two context lifetimes,
including import ownership, budget/foreign-thread guards, binding restoration,
16/32-bit indices, partial-page allocation, ordered pixels and full cleanup.
The unchanged native Spaces code passes 45 state assertions and 273 real-window
checks including the green button, queued reversals, resize/restore and teardown.

`bash tools/verify-terrain-cache.sh JAVA21_HOME CANDIDATE.app` runs the same pressure
fixture on old and fixed signed applications without a renderer overlay. It checks
draw counts, the selected vertex count, exact pixels, page budget, absence of
recovery transfers for the ordinary mixed case and full resource release.
Reported synthetic wall costs are not game FPS. GUI tests require access to the
macOS desktop and must finish before the user's manual game comparison.

The paired signed-APP test passes: the mixed case changes from 2,048 individual
calls in build 185 to 65 multi-draw calls in build 186, with all 2,048 selected
parts pooled, zero pending recovery sources, and no recovery transfers. Both
ordinary cases use 64 calls. All four pixel hashes equal `9ea8483515962325` and
each frame submits exactly 1,048,576 vertices. Mixed-case pooled reservation is
200 MiB; all ranges release after scene deletion. Timing is deliberately not
converted to an expected game FPS or a universal speedup percentage.

The source/launcher regression, compatibility pixel/lifecycle comparison, strict
renderer-boundary audit and synthetic render-cost suite also pass. The renderer
tested from source matches the renderer packaged in the signed APP byte-for-byte.
Packaging validates 86 ARM64 Mach-O files and the ad-hoc signature; it does not
certify notarization or a separate clean Mac.

An isolated, account-free 185 → 186 installer transaction refreshes two original
game JARs and the renderer overlay; repeating it requires no update. The installed
library matches the APP. Installed checks verify 243 changed classes without
initialization, 4,271 routed call sites / seven legacy public ABIs, 237,323 original
algorithm assertions and 106 font assertions. The two policy markers and new
installer marker are checked directly. No account data or main game profile is
read or modified by these installer fixtures.
