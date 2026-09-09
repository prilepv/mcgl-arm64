# Milestone 5 — render architecture / 1.6.12

## Scope and status

**PASS — verified locally on 2026-09-08, version 1.6.12.**
Starts from verified [1.6.11](04-platform.md). This introduces the architectural
boundary for the subsequent rendering migrations, not a replacement visual
pipeline or a Core OpenGL context.

## Architecture

- A JDK-only `RenderDevice` owns context generations and attachment/detachment.
  `RenderContext` owns thread checks, command lifetime and presentation-boundary
  metadata. The platform still owns the native window, CGL lock and context.
- The actual client and GLU call sites are redirected, with unchanged JVM
  descriptors, into typed legacy adapters. Their interface is generated from
  an explicit audited signature manifest, not discovered from the live GPU.
- `LegacyRenderCommands` deliberately retains the existing GL vocabulary while
  the later pipeline/mesh milestones are pending. This is not claimed to be a
  backend-neutral modern mesh API or the complete OpenGL API.
- The compatibility backend alone executes native rendering calls. Commands
  execute immediately on the existing render owner; there is no command queue,
  reflection/boxing dispatcher, state deduplication or reordered drawing.
- Client-array pointer retention and begin/end tracking move into context-owned
  backend state. The old public capabilities flags remain a compatibility view
  of the real driver's immutable feature snapshot.
- GLFW, macOS JNI, CGL synchronization, OpenGL 2.1, dependency versions, gameplay,
  networking and existing geometry/animation/lightmap/VBO algorithms remain.

The inventory of the verified client, patched helpers and GLU contains 171
distinct graphics signatures at 2,915 call sites. The initial manifest also
includes eight additional signatures from the existing GPU regression probe.
Unknown graphics signatures must fail installation rather than bypassing the
new boundary.

## Implementation and lifecycle

`renderer/` compiles independently against the official LWJGL 3 bindings;
neither the legacy window API nor the game is on its compile classpath.
`RenderSystem` selects the compatibility backend. The device, context,
capabilities and command contracts use only JDK types. The platform has no
dependency on the renderer; the existing legacy `Display` adapter coordinates
window presentation and renderer frame metadata.

`renderer/legacy-commands.txt` defines 179 checked signatures in 13 families.
`RenderCommandSpec` validates the manifest and `GenerateRenderBridge` emits
typed adapters, guarded commands and the native compatibility implementation
into a temporary build directory. Runtime dispatch uses ordinary typed calls,
not reflection, varargs, an object command stream or generated-at-startup code.

Each context generation retains its own array pointers and client-attribute
stack. Detaching blocks command use without deleting GPU resources. Reattaching
the same live context retains its legacy capabilities object and resource state.
Destroying it clears retained Java pointers and invalidates cached commands;
foreign-thread, detached and closed-generation calls fail before native GL.
Frame completion records actual dimensions and presentation status; it does
not reset GL state or change drawing order. Existing display-list diagnostics
are preserved through the attachment callback.

The public legacy GL shims remain for binary compatibility. Inherited raw GL
methods still exist in that compatibility surface, but the installed client
and GLU are audited not to use them. This is a deliberately bounded migration
of the verified client, not a sandbox for arbitrary third-party GL code.

## Installer and versioning

Version 1.6.12 / internal build 172. Marker:
`Minecraft Galaxy ARM64 bootstrap lwjgl3-render-architecture-1`.
Installation first performs the existing patches and LWJGL adaptation, then
`PatchMCGLRenderer` redirects graphics method references without changing their
descriptors. The linkage audit runs before staged files are promoted. The
packaged patcher and renderer must contain the same signature manifest.
Unknown signatures or non-static graphics calls are rejected; existing
`lwjgl3-` profiles retain their alphaSort choice.

## Validation

- All existing source regressions pass with Java 21 and the historical LWJGL 2
  API fixture. The existing gameplay/performance helpers, original patchers,
  platform implementation, macOS bootstrap and native GLFW/CGL sources are
  unchanged from the verified 1.6.11 source snapshot.
- 41 headless device checks pass: attachment, ownership, failure rollback,
  generations and frame metadata, with no native renderer library initialized.
- 1,308 dispatch checks execute every one of the 179 packaged adapters and
  guards: exact overloads, distinct arguments, return identity, buffer positions,
  exception propagation and detached-context rejection.
- 1,096 patch checks pass. There are 209 changed classes including a synthetic
  method-handle fixture and 2,467 untouched classes. Bytecode comparison permits
  only graphics-reference changes: descriptors, instructions, locals, control
  flow and resources are otherwise identical. Repatching is byte-identical;
  unknown calls, non-static calls, the binding library itself and output
  overwrite are rejected without leaving a partial output JAR.
- The packaged boundary audit checks 24 renderer classes, 13 adapters, seven
  public legacy ABIs and all 2,915 routed client/GLU call sites. Both negative
  controls fail as expected: the old bundle lacks the renderer, and an old
  unredirected client bypasses it. Linkage audit reports no missing symbols.
- GPU comparison with 1.6.11 passes 42 checks per version with identical
  framebuffer format and fixture pixels across two context lifetimes. The
  candidate probe itself passes through the new renderer adapters.
- 54 native renderer lifecycle checks pass: client-pointer retention on core
  and ARB texture units, boolean-pointer type, client attributes, begin/end error
  checking, frame dimensions/counts, same-context reattachment, persistent
  texture resources, foreign/stale command rejection and cleanup on destruction.
- Platform regressions pass: 118 contract, 45 injected input-backend and 64
  native lifecycle checks. GLFW regressions pass all 80 input and 412 window
  checks, including repeated fullscreen and concurrent textured-drawing/resize
  stress. Native GUI suites were run sequentially.
- Clean installation from the official client and update of a separate 1.6.11
  fixture both pass without an account. The update preserves `alphaSort:false`;
  the actual installed JARs in both fixtures pass the 2,915-call boundary audit.
  Repeating each installer run, including with final package resources, requires
  no changes. Both installed clients retain exactly one post-fullscreen viewport
  synchronization hook after renderer redirection.
- The user reports PASS for server gameplay, moving/loading chunks, entities,
  particles, transparency, inventory, sound, mouse, Cmd+Tab, F11 and window
  resizing, with no noticeable image/FPS regression. The session log confirms
  the compatibility renderer and actual existing VBO/APPLE VAO drawing. After
  disconnect, neither client network thread remains. This is not a controlled
  FPS benchmark or proof of equivalence for every possible game scene.
  Normal close logs `Stopping!` and sound shutdown at 12:11:15 UTC, then process
  completion at 12:11:16 UTC; the isolated runtime PID is no longer present.
- The complete renderer suite and 412-check window stress suite pass again on
  the final package. Its game libraries, native libraries and patch-tool files
  match the manually tested candidate byte-for-byte; every extracted bootstrap
  JAR entry also matches. The Swift launcher is rebuilt from unchanged product
  sources. This is not a claim of whole-container binary reproducibility.
- Final package audit passes: 86 ARM64 Mach-O files, deployment target at most
  macOS 14, strict local signatures, paired Java/JNI interfaces and CGL locking
  on both native sides. ZIP integrity, DMG verification and both SHA-256
  sidecars pass. No original game JAR, account store, credential directory,
  private forum draft or user session log is included. Developer ID signing,
  notarization and installation on a second clean Mac are not certified.

Two initial verification-script compilation failures were fixed in their
classpath/source lists: the render suite needed the packaged patcher classes,
and the source suite needed the renderer manifest/generator dependencies. The
failures are retained in local logs; both complete suites passed after these
test-wiring corrections, with no relaxed assertions or product-code changes.

## Repeating the checks

See [BUILDING](../../BUILDING.md) for prepared inputs and independent renderer
compilation. Run the renderer, platform and window GPU suites sequentially:

```sh
bash tools/verify-render.sh \
  "/path/to/jdk21/Contents/Home" \
  "/path/to/1.6.8/Minecraft Galaxy ARM64.app" \
  "/path/to/1.6.11/Minecraft Galaxy ARM64.app" \
  "/path/to/1.6.12/Minecraft Galaxy ARM64.app" \
  "/path/to/1.6.11-profile/bin/mcgl.jar" \
  "/path/to/1.6.11-profile/bin/lwjgl_util.jar" --live
```

The baseline client/GLU JARs must already have the verified 1.6.11 patches, but
not the new renderer redirection. The 1.6.8 bundle supplies only the historical
API fixture. No login is required for these automated tests.

## Remaining scope

This establishes ownership and an enforced backend boundary. It does not make
the legacy command vocabulary Core-compatible: selecting a Core context alone
would still break fixed-function calls. The next roadmap point requires its
own bounded Core-context work, with the later shader/mesh migrations still
explicitly pending; it is not silently included in this milestone.

No FPS improvement, full Core compatibility, native Spaces fullscreen,
multithreaded rendering/meshing, new chunk renderer or complete removal of
fixed-function GL is claimed here. Nothing was published; the main installed
application and profile were not replaced. Further work requires a separate request.
