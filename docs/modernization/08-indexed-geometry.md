# Milestone 8 — VBO / VAO / index buffers / 1.6.15

## Scope and checkpoint

**PASS for the scoped Core geometry foundation — 2026-09-08.**
Compiled and validated locally in 1.6.15 on Apple M4 Pro / macOS 15.5.
The user requested the first combined checkpoint of milestones 6–8 after this
stage, without a DMG. A separate local APP was built; no release ZIP/DMG was
created and no installed application/profile was replaced.

This is the new explicit Core geometry layer, not a claim that the existing game
now renders in Core. Normal game startup retains compatibility 2.1 and its
existing 2,915 routed legacy call sites. Chunk migration is milestone 9, the
remaining game passes are milestone 10, and legacy removal is milestone 11.

## Geometry contract

- JDK-only `VertexLayout`, `VertexFormats`, `IndexData`, `MeshData`, `Mesh` and
  `MeshPipeline` contracts; LWJGL/native object names remain in the backend.
- Immutable CPU snapshots of interleaved vertices and unsigned 16/32-bit indices.
  Snapshot construction is context-free. Source and returned buffer cursors are
  independent; uploads do not retain CPU mesh data in the GPU resource registry.
- Layouts validate duplicate/overlapping fields, offset/stride alignment and
  bounds. Locations 0–15, 1–4 components, up to 256-byte strides. Float32 and
  signed/unsigned 8/16-bit values feed floating-point shader attributes;
  normalized integer conversion is explicit. Integer shader inputs, instancing
  and multiple vertex streams are not included in this stage.
- Five packed formats match `ShaderLibrary` locations: position vec3, RGBA8
  normalized color, float UV, raw signed-short lightmap UV, and normalized signed
  byte normals with padding. Strides are 16, 24, 28, 28 and 32 bytes respectively.
  Call `layout.requireAttributes(material.requiredAttributes())` when selecting
  a material. Generic custom programs remain the caller's responsibility.
- `IndexData.quads` produces `(0,1,2),(0,2,3)` for each ordered convex quad.
  This preserves face order and winding; it does not sort transparency, reproduce
  unspecified legacy non-planar quad interpolation, or accept incomplete quads.
  Indices automatically promote above 65535; explicitly typed factories also exist.
- A mesh owns one VAO, one VBO and one EBO. Creation uploads and checks storage,
  configures attributes once, and publishes only the complete resource. Failed
  creation deletes partial objects. No client-memory vertex/index pointers.
- Creation preserves the caller's VAO and array buffer; EBO attachment stays in
  its owning VAO. Updates use the Core 3.1 copy-write binding (available in 4.1),
  preserving VAO/EBO/array-buffer state and restoring the copy-write binding.
- Static meshes are immutable. Dynamic/stream meshes accept fixed-size-range
  vertex updates and full same-type/same-count index updates. Every index and
  range is validated before native upload, including overflow-safe range checks.
  STREAM is a driver usage hint, not persistent mapping or an asynchronous queue.
- Indexed points, lines and triangles support subranges and empty no-op draws.
  Each nonempty draw binds its VAO and calls `glDrawElements` with a byte offset.
  No per-draw GL queries, resource allocation, reflection or index conversion.
  Draw leaves the VAO bound and does not change program, textures, blend/depth/
  cull/primitive-restart state. The caller supplies those states explicitly.
- Cached meshes enforce owner, attachment and context-generation lifetime.
  Explicit close deletes GPU resources and unbinds its VAO only if current.
  Whole-context teardown invalidates Java handles after detach; the platform
  context destruction releases native resources without off-context GL calls.

Buffer sizing is fixed: growing a mesh requires creating a replacement and
closing the old mesh. GPU memory pooling, mapped buffers, indirect draws, chunk
rebuild scheduling and new production threads are deliberately not introduced.

## Use

With the existing native macOS bootstrap and `MCGLCoreDisplay.create(...)`:

```java
ShaderLibrary.Material kind = ShaderLibrary.Material.COLOR;
VertexLayout format = VertexFormats.POSITION_COLOR;
format.requireAttributes(kind.requiredAttributes());
MeshData data = new MeshData(format, packedVertices, IndexData.quads(vertexCount));
try (Mesh mesh = RenderSystem.meshes().create("example/quad", data, MeshPipeline.Usage.STATIC)) {
    RenderSystem.materials().program(kind).bind();
    mesh.draw(Mesh.Primitive.TRIANGLES);
}
```

`packedVertices` is a native-order direct buffer whose remaining range contains
complete vertex records. CPU snapshots can be built without a rendering context;
GPU creation, updates, draws and explicit close require the render owner.

## Validation entry points

`tools/verify-core-geometry.sh JAVA21_HOME CANDIDATE.app [--live]` compiles the new
CPU contracts and GPU probe. CPU tests reject invalid layouts, indices, ranges,
types and foreign/detached/stale operations, while checking no native library
is loaded. The live probe runs through the production native runtime with a
synthetic credential and isolated profile; it does not load or authenticate the game.

Live coverage: actual Core version/profile/flags, five GLSL material variants,
shader compile/link/reflection rejection, typed uniforms and unbound-program
writes, explicit RGBA8 pixel checks, model-view/projection and texture/lightmap
matrices, alpha/fog/lighting, VBO/EBO updates, 16/32-bit addressing and indices
above 65535, state preservation, deletion, detach/reattach, resize/presentation
and two full context lifetimes. Default-window GPU previews are saved separately.
This does not establish visual equivalence of every legacy game scene or an FPS gain.

The existing renderer boundary/patch/ABI/equivalence checks remain mandatory.
The native audit constrains mesh calls and GPU-pointer descriptors, rejects
per-draw queries/allocations and retains the old compatibility call inventory.

Version 1.6.15 / build 175. Installer marker:
`Minecraft Galaxy ARM64 bootstrap lwjgl3-indexed-geometry-1`.
Merged library metadata: `MCGL-Geometry-Pipeline: core41/vao-vbo-indexed-v1`.
`MCGL_SKIP_ARCHIVE=1` builds the local app without the optional ZIP; the normal
packaging default remains unchanged. No dependency version or gameplay patch changes.

Changed areas: eight new geometry contract/backend files under `renderer/src`,
integration in `RenderBackend`, `RenderContext`, `RenderSystem` and
`Core41Backend`; renderer build, merged-JAR metadata and package/boundary audits;
`CoreContractsTest`, `CoreGeometryProbe`, the isolated test launcher and
`verify-core-geometry.sh`; version/installer-marker files and their fixtures;
milestone, build and bootstrap documentation. Existing shader sources, game
patches, platform/window/native code and third-party dependencies are unchanged
from the pre-stage-8 snapshot.

Primary API references: [LWJGL buffer operations](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL15C.html),
[VAO operations](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL30C.html),
[copy-buffer targets](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL31C.html).

## Results

| Check | Result |
| --- | --- |
| Independent renderer build against official LWJGL 3 bindings | PASS, generated 179 commands / 13 adapter families; Java 21 with `--release 8` |
| Full local APP build | PASS; all 86 bundled Mach-O files ARM64, macOS deployment target ≤14.0, strict code-signature verification |
| New CPU contracts | PASS, 29,879 assertions (many are repeated quad-index assertions); no LWJGL/GLFW/OpenGL native initialization |
| Real Core GPU probe | PASS, 232 assertions across two full context lifetimes; `4.1 Metal - 89.4`, GLSL `4.10`, Apple M4 Pro |
| Bundled material compilation and pixel fixtures | PASS, all five variants; 11/13/15/19/21 active uniforms respectively |
| Existing renderer device / bridge / bytecode transformation | PASS, 41 / 1,308 / 1,096 assertions respectively |
| Renderer boundary and client linkage | PASS, 62 renderer classes, 179 commands, 2,915 routed call sites, 7 preserved legacy public ABIs; 289 linkage references, 0 missing |
| Compatibility GPU comparison with 1.6.11 | PASS, 42 checks per build, exact fixture pixels/framebuffer format over two lifetimes |
| Compatibility resource lifecycle | PASS, 54 native checks |
| Platform / input CPU regression | PASS, 118 / 45 checks; 3 legacy platform-facing ABIs retained |
| General source/UI/installer/version/patch regression | `SOURCE_CHECK_PASS`, 49 individual result messages plus final summary; all invoked commands passed |
| Negative boundary controls | Old 1.6.11 library rejected as lacking the renderer contract; un-routed client rejected for bypassing the renderer |
| Whitespace / shell syntax | PASS |

The GPU checks use an explicit RGBA8 offscreen target for alpha/color assertions
and a separate default-window preview. Numeric pixels were checked; previews
were also inspected. Expected shader compilation/link/reflection failures are
test cases, not suppressed errors. Native runtime exited 0 with no timeout and
no GL errors in both complete Core lifetimes and the compatibility comparisons.

### Issues encountered at this checkpoint

1. The first test process, run under the terminal sandbox, aborted inside macOS
   `NSApplication` / HIServices `_RegisterApplication` before Java or OpenGL
   initialization. The user saw that crash dialog. Running **the same built
   executable and probe** with GUI-session access completed all 232 checks and
   exited normally. No runtime code or security settings were changed to hide
   the failure. Live GUI probes require access to the macOS window session.
2. The previously unexecuted shader boundary audit counted 33 source call sites
   instead of 39 bytecode sites. `javap` inspection established that Java 8-target
   compilation duplicates six `finally` cleanup invocations (five in program
   creation, one in shader compilation). The audit now checks the exact 39-site
   inventory, still individually allowlisting every native command. The mesh
   backend has 34 compiled native sites, including duplicated cleanup. Pixel,
   ownership, ABI and client-patch expectations were not relaxed.

No production Java/GLSL correction was needed after compilation began. The
native runtime source is byte-for-byte unchanged from the pre-stage-8 snapshot.
Compiler warnings about the intentionally retained Java 8 bytecode target and
existing legacy unchecked operations remain; no dependency versions changed.

### Remaining limits

No authenticated multiplayer/gameplay session, FPS benchmark, clean-Mac build,
fresh full installation, forced GPU-out-of-memory test or exhaustive visual
comparison of legacy game scenes was performed in this checkpoint. Texture
objects and depth/blend/cull state in the GPU probe are test setup, not a new
production texture/state API. Normal game rendering remains compatibility 2.1;
the new foundation is ready for milestone 9, not a completed Core game port.
