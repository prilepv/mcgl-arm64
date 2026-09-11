# Milestone 10 — connected game renderer / 1.6.17

Accepted renderer baseline: build 184 (`lwjgl3-game-original-core-3`). It preserves
the original world/chunk algorithm over owned Core meshes, including the original
local transparency update policy. It does not call the global face planner,
texture tables or combined chunk meshes in the actual world path. The follow-up
adds ordered original-terrain submissions after build 183's text improvement.
Build 183 source, packaged-GPU, installer and installed-JAR checks passed; its
seventh authenticated run reached a user-reported 150–170 FPS at minimum settings
with the HUD. High settings stayed near 27 FPS, with about 6,200 terrain submissions
and 4.5–4.6 million submitted vertices per frame. Making the window smaller did not
noticeably change FPS. This is evidence for pursuing geometry/submission cost,
not proof separating CPU overhead from GPU vertex work or driver waits.
In the eighth authenticated run on 2026-09-09, the user reported 200–230 FPS
at minimum settings and about 50 at high settings, and accepted the result.
This closes milestone 10's local acceptance, not equivalence/performance for every
scene or device. Milestones 11–13 have not been started. Build 185 / 1.6.18 is a
separate [native Spaces fullscreen follow-up](native-fullscreen.md), retaining this renderer.

Build 182 also failed acceptance: the user reported 20–25 FPS at maximum and
80–90 at minimum, versus a remembered 150–200 minimum-setting baseline. A warmed
sample had about 657 terrain and 1,408–1,450 ordinary submissions per frame;
original chunk rebuild work was small in that sample. A selective execution/native
profile attributed 694 of 1,008 sampled draw calls to the two original font classes;
174 of 182 sampled buffer uploads came through the immediate glyph path. These
are stack-sample counts, not GPU timings or percentages of total frame time.

Temporarily hiding the HUD with F1 changed sampled FPS from about 77 to 233–249,
then 100–110 after restoration. Rendering changed from roughly 9–10 ms to 3.3 ms,
then about 8.5 ms, with terrain submissions still near 660. Ordinary geometry,
animation and swap statistics also changed, so this is not a perfectly controlled
font-only A/B. It establishes substantial HUD cost, not that fonts explain the
entire regression. Hidden-HUD FPS is a diagnostic, never the acceptance target.

The fifth authenticated run of build 181 also failed acceptance: the user saw no
improvement. Its filtered counters show roughly 40–182 terrain submissions per
frame, alongside 1,133–2,747 separate ordinary geometry submissions in sampled
windows. Initial loading and a distance-setting transition confound FPS comparisons;
these are not controlled A/B measurements or a CPU/GPU attribution. The reduced
terrain submission count did not establish acceptable game performance.

Build 181 (`lwjgl3-game-core-5`) remains the previous global-order comparison.
It passed automated validation and adds bounded primary-texture tables and
pass-specific invalidation. Separate ordinary/table programs keep spare sampler
costs out of UI and model draws.

The fourth authenticated run also failed acceptance: the user reported low FPS
at both quality settings. After loading, the observed maximum-distance scene
still spent roughly 26–36 ms in rendering, with 4,268 terrain submissions per
frame. A read-only census found all materials eligible for batching, but pass 2
still interleaved five groups in 1,544 runs; pass 0 used 588 runs. A selective
20-second profile also retained planning/sorting costs when chunks were rebuilt.
The follow-up passed focused tests and full APP validation, but did not pass the
fifth authenticated run.

The follow-up uses bounded tables of eight primary textures, stable leased slots
and a packed per-vertex texture-slot/attribute-mask input. It keeps global face
order instead of grouping transparency by texture. Spare units 4–11 are restored
after the pass; no texture image copy or CPU vertex cache is introduced. Only
recognized primary `colorMap` effect lookups with non-aliasing auxiliary samplers
qualify; other effects and sampler mappings retain ordinary batches.
Constant sampler branches use explicit gradients, following
[GLSL 4.10 §§4.1.7 and 8.7](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.10.pdf),
rather than varying sampler-array indices. In the account-free 64×4×6 fixture,
transparent submissions changed from 792 to one and the median from 2.60 to
0.16 ms. Rebuilding only opaque geometry no longer invalidates transparent plans;
the 511×256-face diagnostic changed from 10.06 to 0.42 ms. The initial unified
shader regressed a cached-model case and was replaced by separate ordinary/table
programs; the final cached-model median was 1.77 ms versus 1.90 ms in the baseline.
These are synthetic figures, not a recovered-game-FPS claim.
The focused suite passed 496 game GPU assertions, 416 CPU chunk assertions and
17 CPU table-ownership checks. All 15 ordinary effects and 11 eligible table
variants compiled over two Core lifetimes (76 GPU assertions).

Status: APP 1.6.17 build 179 passed automated validation but failed the third
authenticated-world acceptance run. Reported FPS was 7–18 at maximum and about
70 at minimum; another observation was about 34 stationary, falling to 11 while
turning the camera. Earlier runs also failed: first a slope-geometry exception
and FPS regression; then 7–8 FPS at maximum and about 75 at minimum.
The new draw counters confirm that the intended renderer is installed. Reduced
submission counts did not restore acceptable game performance. Follow-up live
profiling informed build 180; at that point milestone 10 remained incomplete. The main application/profile
remains untouched.
No release, installer archive, primary-profile replacement or FPS claim.

## Connection to the actual game

`DirectLauncher` creates the ordinary game window through `MCGLCoreDisplay`,
before Minecraft starts. Minecraft's own fallback creation is patched too.
The actual context requires Core 4.1; `RenderContext.commands()` still rejects
fixed-function commands in Core. The native runtime's separate Cocoa 2.1 preflight
is not a measurement of the subsequent game context.

`PatchMCGLGame` follows LWJGL/renderer/chunk adaptation in the staged installer
transaction for `mcgl.jar` and the renderer adaptation for `lwjgl_util.jar`.
Renderer acceptance marker: `lwjgl3-game-original-core-3` (build 184).
The separate window follow-up uses `lwjgl3-game-original-core-4` to refresh the
client fullscreen-state hook, without changing renderer algorithms.
The installer explicitly supplies `--original-chunks`; the default three-argument
patcher retains the previous global-order mode for regression comparison. Each
output JAR records `META-INF/mcgl/chunk-policy` as `original-cache` or `global-face`.
Both also record `META-INF/mcgl/text-policy` as `ordered-glyphs-v1`.
Changed original entry points and
unexpected signatures fail before the staged output is published.

The finite game boundary has typed context/thread guards. Old call shapes used
by the obfuscated client are translated into owned Core meshes, GLSL programs,
CPU matrices/material state and Core-valid raster operations. Native immediate
mode, display lists, fixed lighting, matrix stacks and client-memory vertex
arrays are not executed. Removed array/shader entry points fail explicitly.

## Geometry, materials and effects

The original accumulator's default drain now creates indexed Core geometry;
its explicit stage 9 CPU sink remains available. Its vertex emitter and automatic
flush behavior are preserved. Model caches contain owned meshes and bounded state
command groups. Per-vertex current-attribute inheritance and flat quad color/
normal inputs are explicit, including the shared diagonal of both triangles.
Line segments/loops/strips also become indexed strips. Their pixel width is
applied after projection, with draw-time viewport/width and temporary polygon
culling/offset isolation. This preserves game outlines on macOS Core, where the
original native `glLineWidth(2)` was rejected. Cached lines retain dynamic width.

Materials receive CPU matrix stacks, current attributes, lightmap coordinates,
two directional lights, fog and alpha testing. Attribute scopes restore only
their selected state groups. Complete neutral textures occupy otherwise empty
material sampler slots for the duration of a draw.
Bound texture objects' filters/wrap modes are restored by texture scopes too;
initially unbound objects remain untouched, following the legacy state-group
contract ([OpenGL 2.1, §6.1.15](https://registry.khronos.org/OpenGL/specs/gl/glspec21.pdf)).

The original `LA` effect API delegates to `GameEffect`. Original external shader
files are read at runtime, then a finite lexical transformation replaces legacy
GLSL inputs with explicit attributes/uniforms and GLSL 4.10. Unknown builtins
are rejected. Original shader assets are not bundled into public source.
All 15 shipped effect variants have compiled on the actual Core GPU path.

## Ordered original text — build 183

Both independent string entry points of the cached font and the immediate
Latin/Unicode font's string entry point receive explicit, nested text scopes.
The original method bodies, parsing, widths, colors, shadow formulas, page
selection and glyph emission remain unchanged after the normal Core call mapping.
An independent full-method audit checks all 27 font methods and rejects changed
constructor/glyph fingerprints or missing fonts before publishing a patched JAR.

Only adjacent compatible glyphs are combined; textures are never regrouped.
Projection, texture/material/raster changes, custom effects and ordinary geometry
flush the pending prefix first. Each vertex carries the original float model-view
matrix into a separate text shader, retaining the original GPU transform math,
including singular GUI scaling and rotated/world text. The common fragment shader
still provides texture, lightmap, alpha and fog behavior. Ordinary game/effect
programs gain no text attributes. Lit text and custom effects use the existing
Core glyph path, not an approximate replacement or native fixed function.

Each run contains at most 512 glyphs, with exact quad/strip triangle order and
flat-color provoking inputs. Six bounded storage buckets use at most 18 reusable
GPU meshes (under 2.4 MiB), separate from the ordinary 24-key/72-mesh/8-MiB cache.
Only the live index prefix is drawn. The approximately 372-KiB direct staging
buffer is reused; no strings or world vertices are cached by this mechanism.
The cached font's 256 declared glyph handles retain small logical xyz/UV
descriptors (20 floats per glyph), not a general model/world vertex shadow.
An incompatible standalone draw creates that glyph's owned GPU fallback lazily.
Color-command handles are excluded; deletion and context teardown retire both
representations. Thus this does retain explicit font glyph metadata and must not
be described as retaining literally no CPU glyph data.

Completed glyphs preceding a failed string operation remain visible; a partial
primitive is discarded and the text scope is released. Text compiled inside an
unrelated model keeps its existing behavior. GPU tests compare complete RGBA
buffers against the ordinary unbatched path, including actual original Latin and
Unicode methods, both complete cached-string entry points, formatting-buffer
drains, shadows, transforms, texture changes, fog/lightmaps, scope failures,
512-glyph boundaries and two context lifetimes. Diagnostics expose text glyphs
and text submissions separately; they do not imply recovered game FPS.
Text submissions are included in the ordinary geometry counter, not additive
to it; the new expanded glyph vertex count is not directly comparable to the
old four-vertex-per-glyph count.

The same account-free 16-string/2,048-glyph color-and-shadow fixture ran sequentially
against the actual build 182 and 183 packages, without concurrent compilation.
Immediate text changed from 2,048 draws and a 10.664-ms median to 16 draws and
0.876 ms; cached text changed from 5.572 to 0.736 ms with the same draw reduction.
Warmed text created no additional GPU meshes. The separate ordinary 512-model
case measured 1.840 versus 1.793 ms; 96 dynamic batches measured 0.662 versus
0.681 ms. These are synthetic wall times, including driver waits, not a prediction
or measurement of authenticated-world FPS. No earlier global-face benchmark is
presented as the active original-world algorithm.

## Ordered original terrain — build 184

Only consecutive compatible cached mesh draws inside an original `glCallLists`
group are collected. The caller's selection, order, duplicate handles, pass replay,
camera transforms and local transparency policy remain authoritative. A state,
texture, projection, ordinary-geometry or incompatible-page boundary submits the
completed prefix first. Repeating an unchanged depth mask, texture binding or
independent boolean switch does not split a pending terrain run. Color-material
side effects and changed values still retain their ordered barriers.

Original triangle meshes use immutable ranges in shared GPU pages. The original
vertex bytes are copied unchanged at creation, with one extra float at attribute
14 identifying a bounded 32-matrix palette. Every queued mesh captures the exact
float model-view matrix used by the ordinary path. Tag conflicts split runs;
matrices are never approximated by recomputed region/camera positions. A separate
shader variant keeps palette work out of ordinary UI/model/effect programs.
Lighting and imported effects deliberately keep their ordinary Core path.
Singleton runs use the ordinary shader, with the captured model matrix restored
only as a shader input, not as a mutation of logical game state.

The multi-submission uses `glMultiDrawElementsBaseVertex`, preserving the original
index order and UINT32 local indices. Pages contain at most 2,097,152 vertices and
3,145,728 indices; the common 44-byte tagged layout reserves 100 MiB of GPU storage.
All layouts/pages together are capped at 256 MiB. Oversized or over-budget meshes
fall back to independent Core meshes. Independent free ranges coalesce on release;
rebuild publication is still transactional. At most one empty spare page remains
while any members are live, within the same 256-MiB limit; other layouts can evict
the spare before falling back. Unloading the last member releases every page.
This avoids alternating whole-page allocation/deletion when a fully occupied
page must temporarily retain both old and replacement meshes.
No retained CPU world-vertex shadow,
camera-dependent repacking or GPU readback is added to terrain rendering.
The existing explicit generic mesh-combination API remains separate.

New tests compare full RGBA and depth buffers against individually drawn original
cached meshes, including alpha order, repeated handles, more than 32 matrices,
negative/large transforms, texture/lightmap/fog/raster barriers, nested/failing
groups, original primitive modes and fallback paths. They also check allocation
bounds, sparse selection, lifetime isolation and caller state/buffer cursors.
The final source game suite passes 1,192 assertions in each chunk-policy mode
across two Core lifetimes, including 167 terrain assertions per lifetime and
513 repeated models spanning the 256-item run limit. The signed APP passes 450
Core GPU assertions, including bounded spare-page reuse and eviction under budget
pressure. The new saturated-rebuild test fails on the intermediate non-spare
prototype and passes on the final package. This is not an authenticated FPS result.

The first small-page prototype accelerated ordered selections but regressed the
deliberately scattered selection. It was not handed to the user: frequent page
switches defeated multi-submission. World-sized bounded pages address this without
reordering transparency or copying visible geometry each frame. Diagnostic
`MCGL Terrain Runs` reports combined logical parts, CPU multi-submissions and
resident arena storage. A multi-submission is not one underlying GPU draw and
these counters are not GPU timings.

The final identical account-free fixture ran sequentially against the actual
signed build 183 and final build 184 APPs, with no concurrent compiler or game.
It holds 1,024 original chunks with two 256-quad passes each, plus the original
matrix/depth command sequence. Figures include `glFinish` and are wall times,
not authenticated-world FPS or isolated GPU timings:

| Original-cache case | 183 median / p95 ms | 184 median / p95 ms | CPU submissions, 183 → 184 |
| --- | --- | --- | --- |
| Opaque 1,024 chunks | 4.063 / 5.236 | 2.240 / 3.010 | 1,024 → 64 |
| Transparent 1,024 chunks | 3.971 / 4.863 | 1.457 / 1.790 | 1,024 → 64 |
| Three-pass replay | 11.131 / 14.776 | 5.527 / 6.611 | 3,072 → 192 |
| Reversed/changing order | 3.928 / 4.159 | 1.445 / 2.305 | 1,024 → 64 |
| Changing visibility, 768 of 1,024 | 3.136 / 3.391 | 1.080 / 1.236 | 768 → 48 |
| Seeded scattered order | 4.053 / 4.303 | 1.941 / 2.552 | 1,024 → 213 |
| Atomic rebuild, then 1,024 chunks | 4.014 / 4.239 | 1.527 / 1.916 | 1,024 → 64 |

After warmup every case creates zero additional terrain pages or transient meshes.
The intermediate non-spare build's rebuild case measured 4.181 / 6.126 ms in an
earlier run; it is not the final candidate. Ordinary 512-model medians were
1.820 → 1.755 ms, dynamic batches 0.664 → 0.667, immediate text 0.921 → 0.894,
and cached text 0.789 → 0.746. Dynamic/text p95 values varied upward by about
0.4–0.5 ms in this run, so minimum-setting gameplay remains a regression check;
stable medians alone do not prove every frame-time tail is unchanged.

## Original-algorithm control — builds 182–184

The original `H` rebuild and resort bodies, every world-scheduler method, region
batch helper and chunk comparator retain their original instruction flow after
the independently checked graphics-call mapping. Visibility, region/camera
translations, pass order, both `alphaSort` choices and the one-at-a-time local
resort queue are not reimplemented. Original CPU transparency snapshots are
deliberately retained. This preserves the original transparency compromises;
it does not claim globally ordered faces across different chunks.

Old list-shaped calls now address renderer-owned cached Core meshes and state
commands, never driver display lists. A chunk transaction stages only the requested
passes; successful publication retires the replaced meshes, while failed rebuilds
or resorts restore the prior snapshots and renderer state and keep the previous
GPU data. Unchanged passes keep their mesh identity. Turning without an original
rebuild/resort reuses existing buffers; there is no global face planning or GPU
readback/recombination on this path.

Original packed vertices still enter the common accumulator drain. Quads use
40-byte Core vertices (32 original bytes plus flat provoking color/normal inputs);
triangle fans use the original 32-byte layout. Build 184's pooled meshes append
one four-byte palette tag (44/36 bytes). No region-transform or texture-table
attributes are added, and no additional retained CPU vertex cache is introduced.
GUI, models, particles, effects, transient reuse and the Core boundary from the
previous candidate stay in place. Terrain counters classify these cached draws
separately from ordinary geometry draws.

Build 182's `OriginalChunkAdapterTest` checked 237,273 assertions across 2,701 verified methods,
including normalized full-method equivalence, live Core boundaries, transactional
wrappers and rejected patch inputs. The GPU probe tests 34 new assertions per
context: atomic replacement/rollback, partial-pass resort, original accumulator
sort/snapshot replay, exact RGBA, preserved cross-chunk submission order,
quad/fan/quad geometry, unchanged mesh identities and zero index uploads on turns.
Both patch modes pass the full 564-assertion game GPU suite over two Core lifetimes;
the prior global-order CPU/GPU contracts remain tested, not removed.

## Previous global-order world integration — builds 179–181

The following describes the retained comparison mode, not the actual world path
selected by the current installer.

The original `H` block-rendering loops still emit the same packed vertex data.
A transactional rebuild scope captures its passes and material/matrix inputs,
then publishes the complete chunk to `ChunkRenderer`. Failed builds discard the
pending result, reset the accumulator, and retain previously published GPU data.
Disposal/repositioning unloads the corresponding chunk handle.

Original CPU transparent-vertex sorting and snapshot retention are removed from
the rebuild. The old resort entry is inert. The world scheduler's visibility,
occlusion and accounting loop is retained; its selected `H` objects implement a
typed handle and feed the new renderer. Original lightmap hooks and interpolated
camera are retained. Chunk-local bounds/sort centers account for the original
wrapped-origin and model-scale transforms without modifying raw vertex bytes.
Transparent faces are globally ordered within each submitted visible pass;
only changed element-buffer order is uploaded.

Slope blocks also emit triangle fans between ordinary quad batches. The CPU
capture expands fans and triangle strips into independently sortable triangles,
preserving winding, provoking vertices and all original packed inputs. The
first world run exposed this missing topology; the regression fixture now uses
the original accumulator's quad → fan → quad transition.

Compatible opaque and transparent parts share combined GPU meshes. Global face order is
still represented exactly in its element buffer, including interleaving with
incompatible material runs. Separate high-region and low-translation attributes,
plus a camera-region uniform, preserve original object-space shader inputs and
distant/negative origins without splitting groups at coordinate-region boundaries.
Typed depth-mask and 2D texture-bind prefixes collapse to their final values;
texture-unit changes and unknown state are not assumed compatible.
Large groups split at the bounded vertex budget instead of disabling combination
for the entire material. Unrecognized state scopes, non-uniform transforms and
individually oversized sources keep individual Core draws, never fixed-function draws.
Build 179 reused groups only while the visible source membership stayed the same.
An account-free reproduction after the third failed run exposed 12 combinations
in 12 visibility-changing frames despite zero geometry or camera-position changes.
The follow-up fix groups published resident sources independently
of visibility. Looking away retains already-uploaded groups; never-visible groups
allocate no combined buffer. Only the visible index prefix is drawn, with a valid
unused tail preserving storage size and UINT32 index type. Source replacement and
unload still invalidate the affected membership. No CPU vertex cache is retained.
The reproduction now reports zero recombinations. The focused game GPU suite
passes 390 checks, including sparse UINT32 visibility and state restoration.
Full candidate automation passed; authenticated-world validation remains pending.

The macOS Metal-backed driver reported `gldCopyBufferSubData` as unimplemented.
The supported combination path therefore uses temporary buffer readback/repacking
when published source membership changes, with no retained CPU vertex copy.
In the follow-up visibility fix, both camera ordering and selection changes
upload indices, not vertices. This is not a streaming-arena optimization.
Distance-sorted resident face metadata is also reused across visibility changes;
camera translation or geometry publication invalidates it. Empty selection does
not trigger sorting. Complete EBO replacement orphans previous storage after the
same allocation/type/ownership guards; partial vertex updates retain SubData.
Independent enable/disable flags are explicit compatible material-key inputs.
Color-material tracking and other side-effecting or unknown operations remain
ordered individual draws rather than being collapsed to their final flag.

The third live run was profiled stationary and while turning in the same scene:
render wall time rose from roughly 35–38 ms to 65–75 ms. Turning samples included
native buffer readback, allocation/deletion and repeated planning/sorting, while
chunk rebuild totals did not account for that jump. A later, warmed account-free
A/B with 384 of 512 visible 256-quad chunks measured opaque selection changes at
7.23 → 3.09 ms and transparent changes at 10.97 → 4.13 ms. A separate 512-part
boolean-state case changed 512 draws to one (about 2.3 → 0.2 ms). These figures
are synthetic wall times, not recovered game FPS. The intermediate visibility-only
version regressed a smaller transparency case and was not selected for packaging.
Program-owned uniform values are also cached; ownership/type checks still run
before repeated values are skipped. Optional diagnostics now include Core draw
counts per frame and never log scene coordinates or account information.

The second manual run recorded up to 37,483 terrain submissions per frame. A
read-only aggregate sample found 7,874 pass-1 runs sharing only two combined
meshes, plus unbatched texture-bound parts in pass 2. A matching cross-region
fixture reproduced 12,220 / 13,220 draws and 28.51 / 34.05 ms medians; the corrected
path uses one draw and approximately 0.28 ms in both cases. These are synthetic
wall times, not a measurement of recovered server-world FPS.

Selective live JFR recordings contained only execution/native stack samples,
not environment, JVM arguments, network/file events or object contents. They
also showed substantial transient buffer allocation/deletion. Transient UI and
particle meshes now reuse a context-local LRU (24 topology/layout keys, three
meshes each, at most 8 MiB of resident GPU storage). No CPU vertex data is kept.
Whole STREAM replacements orphan the previous store: rotating `SubData` was
pixel-correct but regressed the 96-batch fixture to 5.40 ms, so it was rejected.
The final replacement path measured 0.82 ms versus 1.11 ms for create/delete;
its warmed fixture created no additional mesh objects. Large one-off geometry
retains scoped ownership. This is an integration-regression fix, not the final
streaming-arena/worker/performance milestone.

## Verification and remaining limits

Build 184 passes the complete source/launcher suite, the 1,192-assertion game
GPU suite in both original and regression chunk modes, 450 packaged Core GPU
assertions, 371,283 independent allocation-range assertions, 416 CPU/72 GPU
chunk assertions, all 15 ordinary and 11 eligible table effects, and the complete
compatibility pixel/lifecycle comparison. The 155 packaged renderer class/resource
entries match the final tested source output byte for byte. Native arena calls
have an explicit Core-only allowlist, GPU-offset-only draw signatures and per-draw
query/allocation prohibitions; the strict shader/mesh call inventory includes the
new matrix upload and arena lifecycle calls.

The final isolated 183 → 184 installer transaction updates two game JARs and
the repeat requires no changes. The installed runtime JAR matches the final APP;
both game JARs still declare `original-cache` and `ordered-glyphs-v1`. The installed
boundary audit checks 4,271 routed calls and 150 renderer classes, HotSpot verifies
243 changed client classes without initialization, and the original-method/font
audits pass (237,319 and 106 assertions respectively). The separately signed
Terrain Core Test 8 uses the existing isolated test profile. Earlier APPs,
the primary APP/profile and publication artifacts are not replaced. The eighth
manual run accepted the result at both settings (200–230 minimum, about 50 high).

The initial sandboxed GPU harness aborted in macOS application registration,
before the Java VM started. The same isolated test passed with desktop-session
access. An outdated strict native-call count in the test audit was updated for
the independently allowlisted new API calls; the full audit then passed. Neither
is presented as a successful first attempt, or as an authenticated game failure.

Build 183 passes 858 game GPU assertions in each chunk-policy mode, including
147 new text assertions per context, with exact RGBA comparisons and the actual
original font methods. Its font audit passes 106 assertions over all 27 original
methods; the current original-algorithm audit passes 237,319 assertions over
2,704 verified methods. The complete source/launcher suite, 274 packaged Core GPU
assertions, 416 CPU/72 GPU chunk assertions, all 15 ordinary effects and 11 eligible
table variants (283 CPU/76 GPU input assertions), and compatibility pixel/lifecycle
regression pass. All 148 renderer class/resource entries in the packaged runtime
match the final tested source output byte for byte.

The isolated 182 → 183 installer test updates two JARs and the repeat changes
nothing. Both installed JARs declare `original-cache` and `ordered-glyphs-v1`;
their runtime JAR matches the package. The installed boundary audit passes 4,271
routed call sites across 143 renderer classes; HotSpot verifies 243 changed
client classes without initialization. Both complete-method font and original
world/chunk audits pass against the actual installed client. Test-only compile/
normalization errors were corrected during development; one already-passing GPU
run needed a complete rerun after editing its executing shell script. Both final
policy runs exit successfully. The manual candidate is separately named and
signed Text Core Test 7 and uses the existing isolated profile; the main APP,
profile and earlier test packages are untouched. Its seventh authenticated run
confirmed the user's 150–170 minimum-setting FPS; high-setting work then continued
in build 184, accepted in the subsequent eighth run described above.

Build 182 passed the complete source/launcher suite, 274 packaged Core GPU
assertions, 416 CPU chunk assertions, 72 chunk GPU assertions and the full
compatibility pixel/lifecycle regression. The input suite passes 283 CPU and
76 GPU assertions (15 ordinary effects and 11 eligible table variants over two
Core lifetimes). All 145 packaged renderer class/resource files match the final
564-assertion source game GPU run byte for byte; both chunk-policy modes passed.
An account-free core-5 → original-core installation updated two JARs, and a repeat
changed nothing. Both installed JARs record `original-cache`; the installed
boundary passes 4,271 routed calls across 141 renderer classes, and HotSpot
verifies 243 changed game classes without initialization. The full original-method
equivalence audit also passes against the actual installed client, not just a
source-generated fixture. The first source invocation used the wrong client
input path and the sandboxed installer could not resolve the official host;
corrected-input and network-enabled reruns passed. Neither was a renderer failure.

The earlier manual control used a separately named and signed Original Core Test 6 APP
and the existing isolated test profile. The main application/profile is untouched.
The prior working 1.6.12 compatibility build and build 181 are retained for
same-scene comparison; 1.6.12 is not presented as the original 1.6.7 release.
An additional account-free installation cycle (182 → 172 → 181 → 182) updated
two client JARs at each switch and matched each selected package's runtime JAR.
This validates switching packages, not comparative game performance.

Build 181 passed the full source/launcher suite, 274 packaged Core GPU assertions,
416 CPU chunk assertions, 72 chunk GPU assertions and the complete compatibility
pixel/lifecycle regression. All 144 renderer class/resource files in the packaged
JAR match the successful 496-assertion game GPU run byte for byte. An account-free
core-4 → core-5 installation updated two JARs, and a repeat changed nothing.
The installed files passed the 4,258-call boundary audit (140 renderer classes)
and HotSpot verification of 243 changed client classes. Core Test 5 uses the same
isolated manual profile; the primary application/profile remains untouched.

Build 180 passed 274 Core GPU assertions across two contexts, 390 game GPU
assertions, 370 CPU chunk-contract checks, 72 chunk GPU assertions, 276 CPU
material/effect input checks, the source/launcher regression suite and the full
compatibility pixel/lifecycle comparison. The game adapter passed 241,034 checks
over 2,700 verified methods. An account-free core-3 → core-4 upgrade changed two
JARs; repeating the installation changed nothing. The installed files passed
the 4,258-call boundary audit and HotSpot verification of 243 changed classes.
Core Test 4 used the same isolated manual profile and failed the fourth run.
Those automated results did not establish recovered game FPS.

`tools/verify-game-inputs.sh` covers CPU inputs and actual-GPU materials/effects.
`tools/verify-game-renderer.sh` covers original accumulator/effect integration,
model lifecycle, sampler/raster scopes, chunk submission and global transparency.
Its `--original-chunks` option instead patches and audits the control world's
original algorithms while retaining the same shared CPU/GPU regression suite.
`FontAdapterTest` independently checks original font method equivalence and scope
wrappers; `GameTextProbe` covers ordered batches, actual original font entry points,
pixel/state equivalence, fallback and lifetime bounds. GPU harness scripts accept
an optional `MCGL_TEST_SWIFT_MODULE_CACHE` to reuse compiler framework modules;
they still compile fresh test executables and use new isolated result profiles.
`GameAdapterTest` checks all modified class control flow, live boundary calls,
retained visibility selection, original fingerprints and rejected patch inputs.
`RenderBoundaryAudit` separately allowlists Core raster operations and preserves
the existing shader/mesh ownership boundaries.

The previous APP passed 260 Core GPU checks, 134 game-integration GPU checks,
296 CPU chunk-contract checks, 72 chunk GPU checks and compilation of all 15
original effects (54 GPU assertions). The full source suite and compatibility
pixel/lifecycle regression passed too. An account-free upgrade from the first
Core marker to `core-2`, repeat/no-change installation, actual installed-JAR
boundary audit (4,258 routed call sites) and HotSpot verification of 243 changed
game classes passed. The primary application/profile remains untouched.

`tools/verify-game-render-cost.sh` is a separate Java 21, account-free diagnostic.
On this Mac, its dense 64 × 256-face transparent fixture went from 13,220 draws
and a 39.61 ms median to one draw and 0.272 ms, with both texture slots bound.
These are synthetic wall times, not a measurement of recovered server-world FPS.
The new game-integration fixture passed 326 GPU assertions, including all three
passes across positive, negative and distant region boundaries, camera-region
changes, typed texture restoration, transient reuse, eviction and oversized data.
The signed APP passed 268 Core GPU checks, 304 CPU chunk checks, 72 chunk GPU
checks, all 15 effect compilations, source/launcher tests and the complete
compatibility pixel/lifecycle regression. The actual core-2 → core-3 installed
JARs passed the 4,258-call boundary audit and verification of 243 changed classes;
a repeat install changed nothing. Authenticated acceptance and observation of
resource-combination churn remain required; no recovered game FPS is claimed.

GUI tests require the macOS GUI session. A sandboxed runtime may abort in
`NSApplication` / `_RegisterApplication` before Java: the 2026-09-08 failed
test had an empty game log, and the exact same fixture passed with GUI-service
access (72 GPU checks, two Core lifetimes, normal exit).

The compatibility implementation and unreachable legacy helper methods remain
for reference/regression until milestone 11. CPU chunk workers and completion of
the final buffer-streaming/performance milestone are not claimed here. Component fixtures
do not establish visual equivalence for every block, entity, effect or server
scene; an authenticated world remains a separate acceptance check.

## Lit sign text (build 189, retained in 1.7.1)

The existing ordered text path excluded all fixed-function lighting. Cached
font glyphs used by signs consequently fell back to one ordinary mesh draw per
character. Declared cached glyphs now also batch under ordinary lighting; custom
effects, unrelated compiled models and lit immediate geometry retain their
previous paths. No sign strings, font metrics, glyph order, visibility or game
algorithms are changed.

Lit text uses a separate 172-byte vertex layout, retaining the existing
124-byte unlit layout. Each glyph carries its original float model-view and
inverse-transpose normal matrices, inherited normal, color and lightmap inputs.
Lighting arithmetic is shared with the ordinary material shader, not approximated
on the CPU. Material/light/raster/texture changes remain ordered barriers. Both
layouts share the existing bounded 8 MiB text stream cache and 512-glyph run
limit; no new native calls or unbounded caches are introduced.

The expanded `GameTextProbe` checks exact RGBA against standalone glyph draws,
including directional/point lights, flat/smooth shading, normalization, color
material modes, nonuniform/reflected/singular transforms, per-glyph normals,
lightmap/fog, custom-effect fallback and actual original cached string wrappers.
The account-free 16-sign fixture (four lines of 16 glyphs per sign) produced:

| Path | Draws per fixture frame | Median wall time, two context lifetimes |
| --- | ---: | ---: |
| Standalone lit glyphs | 1,024 | 3.14–3.70 ms |
| Ordered lit glyph runs | 64 | 0.865–0.869 ms |

Both paths produced identical pixels, and warmed runs created no additional
GPU meshes. These synthetic times include `glFinish`; they are not measured
server-world FPS or an authenticated acceptance result. The full game GPU probe
passed 1,602 assertions; material/effect checks passed 76 GPU assertions over two
contexts and all 15 original effects. The 160 packaged renderer entries match
the tested source output. Weather regression checks still pass 118,672 assertions.

The candidate uses installer marker `lwjgl3-game-original-core-7`. An isolated
188-to-189 refresh installed the new renderer without changing graphics options;
a repeat required no updates. Release 1.7.1 retains this renderer with refreshed
release metadata and installer marker `lwjgl3-game-original-core-9`. The later
experimental compiled-model merging path is not included. No server-world FPS
improvement is claimed for this maintenance release.

## Accepted render optimization (1.7.2, build 196)

Release 1.7.2 packages the accepted build 196 renderer unchanged. It combines
word-preserving geometry preparation, bounded compile-time joining of adjacent
compatible model parts, redundant raster-state suppression, and ordered terrain
material batching. This is a separately tested implementation; it does not
reinstate the discarded build 190 or the historical hotpaths experiment.

Compatible original textures may be copied GPU-to-GPU into a bounded array cache
(64 MiB, at most 32 pages). Source updates, sampler state, mip levels, resource
retirement and render-target exclusions are tracked; incompatible inputs retain
the previous path. Wider original-geometry arenas allow up to 320 MiB, and 128
compact matrix tags use the reserved byte of the original 40-byte terrain layout.
Drivers with fewer than 4096 vertex uniform components retain 32 tags. Existing
geometry ordering and local transparent sorting remain unchanged.

Built-in unlit shader variants are selected only when the original logical
lighting state is disabled. Lit paths and imported game effects remain intact.
No persistent CPU shadow of the full GPU geometry or merged-index cache is added.
Installer marker `lwjgl3-game-original-core-14` updates old installations without
resetting their graphics settings. Automated checks and their limits are recorded
in [TESTING](../TESTING.md); no fixed server-world FPS is claimed.
