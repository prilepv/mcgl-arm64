# Milestone 9 — explicit Core chunk renderer / 1.6.16

## Scope and status

**PASS for the explicit chunk pipeline — 2026-09-08, local APP 1.6.16.**
The end-of-stage build, CPU contracts and native GPU checks passed on Apple
M4 Pro / macOS 15.5, Java 21.0.12.1, OpenGL `4.1 Metal - 89.4`, GLSL `4.10`.
This stage adds a working, explicit chunk pipeline and a CPU adapter to the
original game's accumulator. It does **not** switch the ordinary game loop to
Core. The old `net/A/U/H` display-list rebuild/scheduling path remains active
until the enclosing world render passes are connected during stage 10.

The new path is:

1. Original MCGL vertex emission, with an explicitly bound CPU sink.
2. Immutable per-chunk mesh data, preserving each material and terrain pass.
3. Render-owner transactional upload into Core VAO/VBO/index buffers.
4. Frustum culling and indexed draws; global back-to-front transparent faces.

It contains no native display-list fallback or fixed-function emulation.
The compatibility game retains its original draw implementation. No discarded
hotpaths experiment, protocol/gameplay change, worker pool, installed-profile
change, release upload, ZIP or DMG is included.

## Original geometry, not a replacement block mesher

`ChunkMeshBuilder` snapshots the actual eight-word MCGL vertex layout:
position at byte 0, UV at 12, RGBA at 20, packed normal at 24, and two signed
short lightmap coordinates at 28. Its `VertexLayout` points directly at those
fields. Active attributes are preserved bit-for-bit. Disabled arrays use
explicit current-attribute defaults supplied by the caller, not stale words
left in the original scratch buffer. Non-finite positions/UV, incomplete
faces, inconsistent lengths and exceeded rebuild budgets are rejected before
publishing a batch. Positions already contain the original CPU translation.

Both original quads and triangles are accepted. Quads become triangles
`0,1,2 / 0,2,3`, preserving winding. The historical optional convert-quads flag
is rejected explicitly: its duplicated vertices do not preserve all normal
words. It is disabled in the inspected original client. No hidden legacy draw
is used for unsupported topology.

`PatchMCGLChunks` adds `ChunkTessellator` and one per-instance sink to the actual
accumulator. The public draw name/signature remains unchanged. With no sink it
calls the original draw body, preserved in a private helper. With a sink it
submits CPU data and then calls the original reset. Crucially, automatic
capacity flushes call the same public draw and cannot escape to native GL.
Binding/unbinding is only allowed between begun batches; explicit abort clears
an unfinished captured batch without a native operation. Sink failure does
not silently reset/lose its input. There is no per-vertex hook or global
ThreadLocal capture. The disabled path adds a null test and helper dispatch
per draw, not a mesh copy or dual rendering.

The patcher is applied after existing performance, LWJGL and renderer-linkage
adaptations. It fingerprints the original vertex emitter, begin and reset,
checks field layouts, changes exactly one class, and rejects unknown or
already-adapted input. Output is a new staged JAR; a failed validation never
overwrites an existing output. Resources and all other game classes are
unchanged. The installer marker is `lwjgl3-chunk-renderer-1`.

## Ownership, rebuilds and transparency

`ChunkRenderer.request` issues a revision ticket for an aligned 16-block
origin. A new request retains the last resident mesh until a successful
`publish`. Superseded/unloaded/duplicate results are rejected before GPU
allocation; origin or renderer mismatches fail explicitly. If allocation of
one part fails, earlier allocations of that replacement are retired and the
last good chunk remains. Unload and close retire every owned mesh. Context
destruction invalidates retained registries without attempting GL calls on a
dead context. GPU operations require the attached render thread.

The CPU builder is single-use and owned by its constructing thread; its final
data is immutable and can be handed off. No worker execution is introduced:
that is stage 12. The original game singleton is not claimed thread-safe.

Pass zero preserves batch order for opaque/cutout terrain. Passes one and two
sort all visible faces by world-space centroid distance, including faces from
different chunks and materials. Equal distances retain deterministic source
order. Per-part index order is uploaded only when it actually changes;
vertices are never reuploaded for camera movement. Consecutive faces using
the same mesh are drawn in one indexed range, but intervening texture batches
are not regrouped in ways that would corrupt alpha blending. This deliberately
prioritizes correctness over a claimed draw-call reduction. Plans are reused
for unchanged camera/frustum and resident data.

`ChunkFrustum` takes an explicit column-major clip-from-world matrix and uses
double-precision planes/coordinates, with conservative geometry bounds. The
registry retains only GPU handles, bounds, face centroids/order and logical
materials after upload, not original worlds or CPU vertex/index snapshots.

## Enclosing world-pass contract and remaining work

`MaterialBinder` is an explicit boundary: the enclosing frame must resolve the
logical material key to its texture/lightmap, bind the matching shader and
uniforms, apply the supplied chunk origin/model transform, and establish
depth/blend/cull state. The test binder performs these operations on actual
Core GL. Re-entry/mutation of the registry from binder callbacks is rejected.

Stage 10 must connect the original world rebuild/scheduler to this API and
resolve custom block-texture transitions, original world/chunk transforms,
frame state, block entities and the remaining GUI/entity/particle/sky passes.
In particular the original `H` rebuild cannot be called unmodified on a Core
context: it still contains native display-list and matrix operations. Merely
binding the accumulator is not sufficient to migrate that caller. This stage
does not claim that all original block rendering methods are CPU-pure.

The GPU fixture uses the original accumulator's quad and six-face cuboid
emission, including actual attribute packing and capacity flushes. It is not
an authenticated world session or a proof of image equivalence for every
special block. Full-game Core activation, exhaustive scene comparison and
performance/FPS conclusions remain unvalidated.

## Verification

Run with a previously prepared post-1.6.11 client; all adaptations and profiles
are isolated in a new temporary directory:

```sh
bash tools/verify-chunks.sh JAVA21_HOME CANDIDATE.app POST_1611_CLIENT.jar --live
```

The `--live` native probe needs access to the macOS GUI session, as in stage 8.
No account, server connection, installed application or profile is used.

| Check | Result |
| --- | --- |
| Independent renderer build | PASS, compiled against the official LWJGL 3 bindings with Java 21 / `--release 8` |
| Full local APP | PASS, 1.6.16 / build 176; all 86 bundled Mach-O files ARM64, deployment target ≤14.0, strict signatures valid |
| New CPU contracts | PASS, **91 assertions**; real accumulator, repeated automatic flushes, every captured raw word, triangles/cuboids, immutable data, bounds/defaults, tickets, allocation rollback, empty replacement, global sorting across adjacent chunk origins, thread/lifetime guards; no LWJGL/GLFW/OpenGL native initialization |
| New adapter equivalence | PASS, **3,442 checks** (mostly unchanged archive entries); exactly one changed class, original methods restored by independent normalization compare exactly, resources and other game classes unchanged; repeat/overwrite/changed-emitter controls reject |
| New native chunk probe | PASS, **72 assertions**, two full Core context lifetimes and one compatibility lifetime; lightmapped pixels, cross-material transparent interleaving, camera reversal, third pass, frustum, actual indices 65,536–65,539, resource replacement/unload/close/destruction |
| Original-emitter terrain preview | PASS, 260 cuboids / 9,360 indices, actual indexed GPU drawing and window presentation; both images inspected/retained locally, not a game scene-equivalence claim |
| Unbound real accumulator | PASS, original compatibility draw produces the expected color and byte count, with no GL error |
| Previous Core geometry suite | PASS, 29,879 CPU assertions and 232 native GPU assertions, all five material variants |
| Renderer regressions | PASS, device 41 / bridge dispatch 1,308 / patch equivalence 1,096; boundary audit 81 renderer classes, 179 commands and 2,915 routed client/GLU calls; native inventory unchanged |
| Compatibility GPU comparison | PASS, exact format/pixel/fixture hashes against 1.6.11 across two lifetimes each; 54 lifecycle assertions; all native runtime exits zero, no timeout |
| Platform regression | PASS, 14 platform classes / 4 adapters / 3 retained public ABIs against 1.6.11; 118 platform and 45 input assertions |
| Binding linkage | PASS, chunk-adapted client 265 references / zero missing; combined client+GLU 289 / zero missing |
| Complete source regression | PASS, 49 fixture/test PASS records plus final `SOURCE_CHECK_PASS` (50 PASS lines, not 50 independent assertion counts); launcher/accounts/preferences/installer migration and original game patches included |
| Final hygiene | PASS, shell syntax, whitespace checks and strict APP signature verification; native runtime, native mesh and native shader sources unchanged from the 1.6.15 checkpoint |

The initial preflight found a test-only call to the wrong `RenderDevice.detach`
signature; the harness was corrected to use the existing no-argument method.
No expected rendering behavior was relaxed and no production correction was
needed after the initial compilation. GPU tests used the GUI-session permission
established in stage 8 and did not reproduce its sandbox startup crash.

No regression was detected within these checks. A full fresh network install,
authenticated world session, all special-block rendering paths, controlled
FPS/frame-time comparison and validation on a separate clean Mac were **not**
performed in this stage. Ordinary gameplay still takes the compatibility
path. Readiness for stage 10 is the tested explicit chunk API, not a claim
that connecting all world/render callers is already done.

## Changed areas

- Renderer: `ChunkMaterial`, `ChunkTessellator`, `ChunkMeshBuilder`,
  `ChunkMeshData`, `ChunkFrustum`, `ChunkRenderer`; existing shader/native mesh
  implementations are not rewritten.
- Adapter/build: `PatchMCGLChunks`, installer staging/marker, renderer packaging
  metadata, package audit, version 1.6.16 / build 176.
- Tests: chunk contracts, original-emitter test helper, patch equivalence,
  GPU probe, isolated bootstrap and `verify-chunks.sh`; version migration tests.
- Documentation: this report, roadmap, build guide and development bundle README.
