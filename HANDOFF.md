# Handoff: VulkanMod port for Minecraft 26.1.2 / NeoForge + Iris-compatible shader pack support

Two efforts live in this repo:
1. The base VulkanMod port to **Minecraft 26.1.2 / NeoForge** (rendering, previously buggy, now working).
2. A **shader-pack transpiler → SPIR-V → in-game rendering** path so real packs (Complementary Reimagined r5.8.1) can run. Phase 1 (compile) is done/green; Phase 2 (in-game) is in progress.

## Project
- Repo: `/home/riley/dev/minecraft_dev/VulkanMod`, branch `neoforge-26.1.2` (user runs via official launcher + NeoForge).
- Ground-truth pack for dev/test: Complementary Reimagined r5.8.1 at `/tmp/opencode/cpl`.
- `VulkanModNeoForge/` folder inside the repo is an OLD reference port (1.21.4) — ignore it.
- Config: `run/config/vulkanmod_settings.json`. In-game toggles under Esc -> Video Settings -> Optimizations.

## Base-port current state (verified working)
- Game boots, joins a world, renders SOLID/CUTOUT/TRANSLUCENT terrain + fluids + skybox. Do not regress.
- Root causes fixed earlier (still relevant, don't regress):
  - MC 26.1.2 `DefaultVertexFormat.BLOCK` dropped per-vertex Normal: 28B = 7 ints/vertex (Position, Color, UV0, UV2). VERTEX_STRIDE=7, TOTAL_STRIDE=32; offsets X=4..LIGHTMAP=10; offset 11 is vertex1.X, never read/write. Fixed in `MutableQuadViewImpl.fromBakedQuad/fromVanilla` (`normal()`/`populateMissingNormals()` now no-ops, null-guard for `QuadFacing`).
  - Solid terrain invisibility: build remap vs draw-set mismatch. Fix: `TerrainRenderType.SEMI_COMPACT_RENDER_TYPES` (always draws SOLID/CUTOUT/TRANSLUCENT). NOTE: `uniqueOpaqueLayer` config no longer gates draw passes.
  - Skybox white-box artifact resolved; `terrain_early_z` path rename fixed datapack warning; SPIR-V attr location mapping updated in `SpirvPipeline.java`.
- Remaining base-port issues (lower priority than shader packs):
  - SIGSEGV at shutdown in `vkDestroySwapchainKHR` (NVIDIA `libnvidia-glcore.so.595.84`), `run/hs_err_pid*.log`. Partially addressed (swapchain/surface release ordering in `SwapChain` + `MinecraftMixin`).
  - Game auto-quits ~60s after joining ("Dev lost connection"), `MinecraftMixin.stop` logs a stack trace — check the latest log to find the trigger.

## Shader pack path — architecture
Data flow: pack GLSL (`program/*.glsl` with `#version 130`-era OptiFine/iris idioms) → `ShaderProcessor` transpile → tiny virtual entry shader (`#version 450\n#include "name"`) → `SpirvCompiler` (shaderc) → SPIR-V → Vulkan pipeline, with reflection (`SpirvShader.createFromSpirv`) driving UBO/sampler/interface layout.

- Emulation strategy: legacy `gl_` tokens are RENAMED to `vm_gl*` (glslang reserves `gl_`). Pack uses its own `gbufferModelView`, `sunPosition`, etc. uniforms via in-shader UBOs.
- UBO layout (per program, both stages, standard 140):
  - **binding 0** `PackUniforms` (all 129 pack uniforms, e.g. 1728 bytes for gbuffers_terrain).
  - **binding 1** `LegacyMatrices` (vm_glModelViewProjection/ModelView/Projection/ModelViewInverse/ NormalMatrix/TextureMatrix + fog members + vm_cameraPosition).
  - **binding 2** `vm_SectionData` = the BUILT-IN terrain UBO (`ivec4 SectionOffsets[128]; vec4 SectionFadeFactors[128]`, 4096B; `setUseGlobalBuffer(false)`, bufferSlice set by `DrawBuffers.bindBuffers`), reused unchanged by pack terrain passes.
  - **sampler bindings start at 3** (frees binding 2 for SectionData) — internal per-name sharing via `samplerBindings` map so vertex+fragment agree (shaderc maps `texture(colortex0,..)` → `Sampler0`, `colortex0`=binding 3, etc.).
- Push constants: `vec3 ModelOffset` (camera-relative area origin) at offset 0.
- Pack program computation `position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex` requires `gl_Vertex` WORLD-ABSOLUTE → vertex header computes `local + ModelOffset + sectionBaseOffset + cameraPosition` (offset decode replicated from built-in `terrain.vsh`: `bitfieldExtract(ivec3(enc)>>ivec3(0,16,8), 0, 8)`, `enc = vm_SectionOffsets[gl_InstanceIndex>>2][gl_InstanceIndex&3]`).

## Work state
### Completed
- **Phase 0 — pack infrastructure**: `Config.shaderPack`, `ShaderPackManager.init(FMLPaths.GAMEDIR.get())` in `Initializer.onInitializeClient`, `FolderShaderPack`/`ZipShaderPack`, `ShaderProperties` (parses `shaders.properties`, lists passes per dimension), `Options` load/save. `shaders/` transpiler package + test harness layout.
- **Phase 1 — transpile to SPIR-V (GREEN)**: `./gradlew runShaderPack --args="/tmp/opencode/cpl <prog> world0"` compiles; `program=ALL` sweeps `shaders/program/*.glsl`:
  - **31/31 programs × 3 dimensions (world0/world-1/world1) compile to SPIR-V**; `dh_*` excluded (Iris debug-helper, `dhMaterialId` undeclared). `./gradlew compileJava` clean.
  - Fixed in `ShaderProcessor`: CRLF stripping; reserved `gl_`→`vm_gl` rename; per-stage preprocessor guard tracking (`Region` stack + `evalStageCond`); include flattening (included once per include-site, no global pragma-once — packs legitimately double-include); in-place sampler name rewrite; `ftransform()`→`(vm_glModelViewProjectionMatrix * vm_glVertex)`; `rewriteShadowAccess` (strips `.x/.z` after `texture(shadowTex,...)` on sampler2DShadow — OptiFine idiom); image qualifiers (`readonly/writeonly/coherent/volatile/restrict`) tolerated and `writeonly` preserved for `image2D/uimage2D`; `gl_Fog.*`→`vm_glFog_*` members.
  - Reflection validated via `SpirvShader.createFromSpirv`; vertex outputs == fragment inputs (mat/texCoord/lmCoord/midCoord/normal/vertexPos/... = locations 0..12).
- **GOTCHA**: `SpirvShader.close()` calls `memFree` on shaderc-owned memory → SIGSEGV in libjemalloc. Test must NOT close reflected shaders (no try-with-resources). Keep shaderc-owned ByteBuffer alive in runtime too (shaderc result release is commented out in `SpirvCompiler`).

### In progress — Phase 2 Slice 1 (render `gbuffers_terrain` in-game with pack pipeline)
- Design (decided, partially implemented see below):
  - Use the existing UNCOMPRESSED `CustomVertexFormat.TERRAIN` (32B: Position vec3f + Color uint + UV0 vec2f + UV2 vec2f lightmap + Normal packed int) via `VertexBuilder.DefaultVertexBuilder` — no mesh-builder change; switch via `PipelineManager.setTerrainVertexFormat`. Default today is `COMPRESSED_TERRAIN` (16B, int16 pos + light nibbles in Position.w + ushort uv0 + uint color).
  - Header emits legacy inputs as private inputs + `#define`s (`vm_glVertex` etc.), gated `#ifdef VM_TERRAIN_SHIM` for the SectionData/push-constant/camera block (runtime defines the macro at compile time).
  - `gl_TextureMatrix[1]` must become SEPARATE identity `vm_glTextureMatrixLight` (current flattening to one matrix is wrong: index 1 is the lightmap basis in `GetLightMapCoordinates()`). Feed `vm_glMultiTexCoord1.xy = uv2/256` so light level k → k/15.
  - `mc_Entity` (loc 0) + `mc_midTexCoord` (loc 1) as INSTANCE-RATE dummy attributes (constant vec4(0)/vec2(0)) — blockId=0 world identity for now; `TerrainBuilder.setBlockAttributes(BlockState)` is an empty hook for real blockIds later.
  - `PackUniforms` uploaded via `ManualUBO` (binding 0, `setSrc` memCopy hook in `UBO.update(ptr)`); uniform pump writes the 1728-byte buffer each frame from name→std140 offset (`ShaderUniform` records) + game state (camera, sun, fog, time), unknown → 0. `LegacyMatrices` pumped from camera/view/proj.
- **NOT YET IMPLEMENTED (next)**: section-offset shim + private input macros in header, `vm_glTextureMatrixLight`, sampler-start-at-3 shift, cameraPosition/fog members, PackUBO + uniform pump, pack `GraphicsPipeline` builder with custom VertexInputDescription mapping TERRAIN elements→pack locations (Position→4, Normal→5, Color→6, UV0→7, UV2→8) + instance-rate constants for loc 0/1, terrain-draw routing through pack `gbuffers_terrain`, samplers bound (tex→atlas, lightmap, others white fallback).

## Commands
- `./gradlew compileJava` — must stay clean.
- `./gradlew runShaderPack --args="/tmp/opencode/cpl <program|ALL> <world0|world-1|world1>"` — transpiler/compile harness (task name is `runShaderPack`).
- `./gradlew runClient` — in-game smoke test.
- Pack program list (31): composite, composite1–7, deferred1, final, gbuffers_{armor_glint,basic,beaconbeam,block,clouds,damagedblock,entities,hand,lightning,skybasic,skytextured,spidereyes,terrain,textured,water,weather}, shadow, shadowcomp, template, voxy_opaque, voxy_translucent.

## Key files
- `src/main/java/net/vulkanmod/shaders/transform/ShaderProcessor.java` — transpiler (EN) + header emission / LegacyMatrices / sampler bindings (next edits here).
- `src/test/java/net/vulkanmod/TestShaderPack.java` — harness: `ALL` sweep, `runProgram`, `printReflected`.
- `src/main/java/net/vulkanmod/vulkan/shader/SpirvCompiler.java` (virtual includes, SPIR-V out) and `vulkan/shader/converter/SpirvShader.java` (reflection).
- `src/main/java/net/vulkanmod/vulkan/shader/` — `Pipeline.java`, `GraphicsPipeline.java` (VertexInputDescription), `PipelineConfig.java`, `Uniforms.java`, `DescriptorSets.java`, `descriptor/UBO.java`, `descriptor/ManualUBO.java`, `layout/PushConstants.java`.
- `src/main/java/net/vulkanmod/render/shader/PipelineManager.java` — `terrainVertexFormat` switch.
- `src/main/java/net/vulkanmod/render/vertex/` — `CustomVertexFormat.java` (TERRAIN 32B), `VertexBuilder.java`, `TerrainBuilder.java` (blockId hook).
- `src/main/resources/assets/vulkanmod/shaders/basic/terrain/terrain.vsh` + `include/light.glsl` — reference section-offset/lightmap logic to replicate in the header shim.
- `src/main/java/net/vulkanmod/render/chunk/WorldRenderer.java` + `chunk/buffer/DrawBuffers.java` — terrain draw + SectionData upload path.
- `/tmp/opencode/cpl` — Complementary r5.8.1 ground truth (`program/gbuffers_terrain.glsl`, `lib/uniforms.glsl`, `shaders.properties`, `world0/...`).

## Next steps (Phase 2 Slice 1)
1. Read `Renderer.pushConstants`, `DrawBuffers.bindBuffers`, `VRenderSystem`/`Uniforms.java`, `VTextureSelector`, `GameRendererMixin`/`LevelRendererMixin` — pin down exact APIs for pack-pipeline wiring (mid-read when last session ended).
2. Implement header/macro changes in `ShaderProcessor` (section shim, TextureMatrixLight, sampler start 3, cameraPosition/fog members).
3. PackUBO + uniform pump; pack `GraphicsPipeline` builder (custom vertex-input loc mapping + instance-rate constants).
4. Route terrain draws → pack `gbuffers_terrain` when pack active; bind tex/lightmap samplers.
5. Re-run ALL sweep (layout/sizes change) + `./gradlew runClient` smoke.

## Git state
- HEAD is upstream `087ef24`. Working tree has ~108 STAGED-but-uncommitted port changes + new untracked `src/main/java/net/vulkanmod/shaders/`, `src/test/`, `logs/`. No git identity configured — set repo-local `user.name`/`user.email` and commit as a safety checkpoint before risky work.