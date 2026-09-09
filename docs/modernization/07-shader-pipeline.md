# Milestone 7 — GLSL shader pipeline / 1.6.14

## Status and scope

**SHADER FOUNDATION VALIDATED IN THE 1.6.15 CHECKPOINT — 2026-09-08.**
Continues the [Core 4.1 foundation](06-core41.md). Originally implemented without
intermediate compilation or tests at the user's request. The later
[milestone 8 checkpoint](08-indexed-geometry.md) compiled the combined sources,
all five GLSL variants and checked actual GPU pixels, failures and resource
lifetimes. No standalone 1.6.14 package was produced; full game migration is pending.

The new pipeline is accessible on an explicitly created Core context. Normal
game startup and its 2,915 legacy graphics call sites remain on the existing
compatibility path. Mesh submission is now supplied by milestone 8; production
texture/state integration and the eventual game entry-point switch remain
separate work. Adding GLSL alone does not migrate fixed-function drawing.

## Program and uniform lifecycle

- `ShaderSources`, `ShaderPipeline`, `ShaderProgram` and `ShaderUniform` expose
  JDK-only contracts. Native program names, uniform locations and LWJGL objects
  remain inside `NativeShaderPipeline`.
- Shader creation is transactional: vertex/fragment compilation, link status
  and uniform reflection complete before a program is returned. Failed builds
  delete partial shader/program objects. Shader objects are detached and
  deleted after linking. Compiler/linker messages identify the label and stage,
  with each retrieved driver log bounded to 8 KiB.
- Uniform metadata is read once after link, not looked up through GL on each
  frame. Supported types are int, bool, sampler2D, float, vec2/3/4 and mat3/4.
  Wrong-type writes, invalid bool/negative sampler values and invalid matrix
  buffers fail before upload. Matrices are column-major, exactly one matrix per
  call, in native-order direct buffers; buffer position/limit are not changed.
- Uniform updates use the OpenGL 4.1 program-targeted API, so preparing one
  program does not require binding it or overwrite another program's uniforms.
  This follows the [LWJGL GL41C contract](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL41C.html).
- Programs and cached uniform wrappers retain the render context's ownership
  and generation guards. Detached, foreign-thread and destroyed-context use is
  rejected. Same-context reattachment keeps existing programs and values.
- Explicit program close unbinds it only if it is current, then deletes it.
  During whole-context destruction, the backend invalidates Java handles after
  detachment; native resources are released by destruction of the platform
  context. It does not issue GL deletion calls without a current context.

Uniform arrays, uniform blocks, other sampler kinds, program binaries, hot
reload, separable program-pipeline objects and compatibility-context support
for this new API are not implemented. Unsupported active uniforms are rejected
with cleanup, not silently ignored. `findUniform` returns null for uniforms
removed by optimization; `uniform` requires an active uniform.

## Bundled materials

`ShaderLibrary` lazily creates five variants from the bundled
`material.vert` / `material.frag` sources using GLSL `#version 410 core`:

- `COLOR`: vertex color and color modulation.
- `TEXTURED`: color multiplied by a projected 2D texture sample.
- `LIGHTMAPPED`: the textured path additionally multiplied by a lightmap sample.
- `LIT_TEXTURED`: textured color with two directional diffuse lights and ambient.
- `LIT_LIGHTMAPPED`: directional lighting combined with the textured/lightmap path.

All variants expose explicit model-view/projection transforms, alpha comparison
and fog parameters. Texture and lightmap coordinates use separate texture
matrices, including projective coordinates. Lighting uses the supplied normal
matrix and normalized eye-space light directions; it is per-vertex diffuse
lighting, not a complete fixed-function material/lighting emulator.

Alpha modes cover disabled, never, less, equal, lequal, greater, notequal,
gequal and always. Fog covers disabled, linear, exponential and exponential
squared, with eye-Z or radial distance. Fog changes RGB, not alpha. Color-space
conversion, blend/depth/cull state and texture/sampler-object state are not
implicitly changed by this layer.

The shader inputs define the next geometry layer's contract: locations 0–4 are
position vec3, color vec4, texture UV vec2, lightmap UV vec2 and normal vec3.
Each material exposes its required attribute mask. Texture sampler units are
0 and 1. Raw lightmap coordinates must be transformed by the caller's actual
lightmap matrix; no guessed Minecraft-specific scaling is hardcoded.

Initial values are identity matrices, white modulation, disabled alpha/fog,
texture units 0/1 and neutral lighting. These are deterministic API defaults,
not assertions that existing game state has already been translated. Visual
equivalence, including alpha precision, fog interpolation, lighting and edge
cases, must be established during the remaining renderer integration.

## Source-level use

This example has **not been compiled or run**. It requires the existing macOS
bootstrap and an explicitly created Core context from milestone 6:

```java
ShaderProgram material = RenderSystem.materials().program(ShaderLibrary.Material.LIGHTMAPPED);
material.uniform("uModelView").setMatrix4(modelView);
material.uniform("uProjection").setMatrix4(projection);
material.uniform("uAlphaFunction").setInt(ShaderLibrary.AlphaTest.GREATER.id);
material.uniform("uAlphaReference").setFloat(0.1f);
material.bind();
// Bind textures and submit a matching mesh through the forthcoming geometry layer.
```

`RenderSystem.shaders().create(sources)` creates a custom program using the same
guarded lifecycle and supported uniform contract. `ShaderLibrary` is owned by
`RenderContext`; it can also be closed explicitly to release its programs.
Closing a program invalidates previously retrieved uniform wrappers.

## Integration and versioning

Source version 1.6.14 / internal build 174. Installer marker:
`Minecraft Galaxy ARM64 bootstrap lwjgl3-glsl-pipeline-1`.
The existing `lwjgl3-` preference migration rule remains. No gameplay patch,
client-link manifest, dependency version, native window/CGL implementation,
network protocol or production thread is changed.

New files are the shader contracts, guarded factory/program/uniform wrappers,
`ShaderLibrary`, `NativeShaderPipeline` and two shader resources. `RenderBackend`,
`RenderContext`, `RenderSystem` and `Core41Backend` connect their lifecycle.
The renderer build copies resource files into the existing module/JAR; the
merged library declares `MCGL-Shader-Pipeline: glsl410/materials-v1`.
Version, installer, documentation and deferred audit fixtures are updated.

The boundary audit retains the old compatibility inventory, checks the new
native shader calls against a Core-only allowlist and counts them separately.
The package audit requires the corresponding contracts and resources. These
checks were subsequently executed in milestone 8. Its report documents the
corrected source-site versus compiled-bytecode cleanup inventory.

## Validation and remaining integration

Milestone 8 compiled Java and all five GLSL variants on the actual Core 4.1
driver and checked malformed-source/link/reflection rejection, required/absent
uniforms, typed writes, matrix-buffer rejection, unbound-program writes,
detach/reattach, foreign/stale handles and deletion of current/non-current programs.

Its pixel fixtures cover textured/color, lightmap, transformed/projective UVs,
alpha/fog modes and lighting. Full legacy-scene equivalence, complete server
gameplay, sound and performance still require the remaining game integration.
Synthetic results do not establish an FPS gain or complete visual equivalence.

Primary API references: [shader/program operations in GL20C](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL20C.html)
and [Khronos program deletion semantics](https://wikis.khronos.org/opengl/GLAPI/glDeleteProgram).
