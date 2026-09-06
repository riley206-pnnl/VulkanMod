# Handoff: VulkanMod port for Minecraft 26.1.2 / NeoForge

You're taking over a working Minecraft-mod port and need to finish/fix a few remaining in-game bugs.

## Project
- VulkanMod ported to **Minecraft 26.1.2 / NeoForge** (the user runs it via the official Minecraft
  launcher with NeoForge + the VulkanMod jar built from this repo).
- Repo: `/home/riley/dev/minecraft_dev/VulkanMod`, branch `neoforge-26.1.2`.
- Dev-run locally with: `./gradlew runClient` (opens a NeoForge dev client; open a world to test).
  Compile-check with `./gradlew compileJava`.
- The folder `VulkanModNeoForge/` inside the repo is an OLD reference port (1.21.4) — NOT the
  working code. Ignore it.
- Config lives in `run/config/vulkanmod_settings.json`: indirectDraw=true, uniqueOpaqueLayer=true,
  backFaceCulling=true, advCulling=2, builderThreads=0, frameQueueSize=2. Most options are also
  live-toggleable in-game under Esc -> "Video Settings…" -> "Optimizations" tab.

## Current state (verified working)
- Game boots, joins a world, and now renders SOLID terrain blocks AND water/lava (fluids).
- The big previous problems (see below) are resolved. Do not regress them.

## What was already fixed / root causes (very important context)
1. MC 26.1.2 changed `DefaultVertexFormat.BLOCK`: it DROPPED the per-vertex Normal attribute.
   Block quads are now 28 bytes = 7 ints/vertex (Position, Color, UV0, UV2). It used to be 8.
   - Terrain mesh format constants: VERTEX_STRIDE=7, TOTAL_STRIDE=32 (4-int header + 4x7).
   - Correct vertex offsets in the quad data array: X=4, Y=5, Z=6, COLOR=7, U=8, V=9, LIGHTMAP=10.
     Offset `11` is now vertex1.X, NOT a normal — never read/write it.
   - The old write path (used `baseIndex + HEADER_STRIDE` and wrote a normal) caused an
     ArrayIndexOutOfBoundsException (index 32) on every block build. It's fixed in
     `src/main/java/net/vulkanmod/render/chunk/build/frapi/mesh/MutableQuadViewImpl.java`
     (`fromBakedQuad`, `fromVanilla`), plus `normal()`/`populateMissingNormals()` are now no-ops
     and a null-guard was added for `QuadFacing` (facing = fromDirection(lightFace) when null).
2. Solid terrain was INVISIBLE because of a build/draw layer mismatch:
   - The build (`BlockRenderer.renderBlock`) hardcodes writing block geometry into the **SOLID**
     TerrainBuilder. The SOLID->CUTOUT remap (`TerrainRenderType.getRemapped`, driven by
     `uniqueOpaqueLayer`) only takes effect for quads that return a non-null `renderLayer()`.
   - The draw (`WorldRenderer.renderSectionLayer`) only iterated CUTOUT/TRANSLUCENT passes when
     `uniqueOpaqueLayer=true` (COMPACT_RENDER_TYPES), so the fully-built/uploaded SOLID layer was
     never drawn.
   - Fix applied: `allowedRenderTypes = TerrainRenderType.SEMI_COMPACT_RENDER_TYPES` (always draws
     SOLID, CUTOUT, TRANSLUCENT). Verified: `INDIRECT_DIAG SOLID drawCount=16..26`, terrain visible.
   - NOTE: this means `uniqueOpaqueLayer` no longer does anything meaningful for the draw pass set.
     If you want that perf feature back, the correct fix is to make the BUILD remap apply to ALL
     quads (honor renderLayer for solids), then you could go back to gating by config.

## Remaining bugs to fix (handoff list)
1. GRASS / FLOWERS / CROSS-SHAPED PLANTS DON'T RENDER PROPERLY.
   These are CUTOUT-layer blocks (cross/plant models with alpha-cutout textures). Hypothesis
   (fits the same class of bug as #2 above): the CUTOUT layer has the same build-vs-draw
   mismatch, OR the CUTOUT pass draws but the vertex data/alpha handling is wrong.
   Verify first with one-shot logging (see "Diagnostics" below): does the CUTOUT UploadBuffer get
   vertices at build time, do CUTOUT draw params have indexCount>0, and does the CUTOUT pass run?
   Then check the alpha-cutout uniform (`VRenderSystem.alphaCutout`, set via
   `renderType.setCutoutUniform()`, SOLID=0.0, CUTOUT=0.5) and the fragment shader discard logic.
2. WHITE BOX IN THE SKY is still visible. Unknown cause. It was present before and after terrain
   rendering was fixed. Likely candidates: the sun/moon (skybox) quad drawn with a broken
   shader/transform, or one garbage/degenerate quad. Ask the user for a screenshot; check the
   sky/sun render path in `src/main/java/net/vulkanmod/mixin/` (clouds, sky, celestials) and the
   `rendertype_sun`/sky pipelines.
3. NATIVE CRASH AT SHUTDOWN (game fully exits): SIGSEGV in the NVIDIA driver
   (`libnvidia-glcore.so.595.84`) at `vkDestroySwapchainKHR`. Occurs after a clean "Stopping!".
   hs_err log: `run/hs_err_pid*.log`. Likely swapchain/fence teardown-ordering bug (double
   destroy or a reference used after free). Driver 595.84, NVIDIA.
4. GAME AUTO-QUITS ~60 SECONDS AFTER JOINING a world, all by itself: logs show clean
   "Saving and pausing game..." then "Dev lost connection: Disconnected" then "Stopping server".
   Unexplained; looks deliberate, not a crash. Find what triggers it (search for auto-quit /
   IntegratedServer stop / a watchdog in the port, possibly a dev-only hook).
5. STARTUP WARNINGS/ERRORS (probably benign but worth cleaning up):
   - "Invalid path in datapack: vulkanmod:shaders/basic/terrain_earlyZ/..., ignoring" — the
     terrain_earlyZ shader files exist under assets; the early-Z pipeline
     (`PipelineManager.terrainShaderEarlyZ`) is created but NEVER used (all terrain render types
     map to `terrainShader` via the default shaderGetter). Consider fixing the pack-path warning
     or removing the unused earlyZ pipeline.
   - "Missing attributes: [UV2]" / "[UV1, UV2, Normal]" and "Shader expects inputs which are not
     being provided: [fAnimationProgress]" — SPIRV pipeline validation noise
     (`vulkan/shader/converter/SpirvPipeline.java`). The terrain shader expects UV2 (lightmap) but
     COMPRESSED_TERRAIN (Position/UV0/Color) has no UV2/Normal attributes; rendering works anyway.

## Diagnostics you can re-add quickly (one-shot logs, removed before the checkpoint)
- In `DrawBuffers.upload`: log per-facing vertex counts + indexCount per renderType.
- In `DrawBuffers.buildDrawBatchesIndirect/Direct`: log drawCount per renderType.
- In `BlockRenderer.bufferQuad`: log the first quad's coords/UV/light/facing.
- In `WorldRenderer.renderSectionLayer`: log areas/drawn/noBuffer/emptyQueue per renderType.
The SOLID-area draw stats line already exists in WorldRenderer ("VulkanMod DRAW: ...", fires only
for SOLID; extend it if needed).

## Data model quick reference
- Terrain vertex format: CustomVertexFormat.COMPRESSED_TERRAIN = Position SHORT(x4, offset 4.0,
  conv 2048), UV0 USHORT(x2, conv 32768), Color UINT; 16-byte stride.
- TerrainRenderType: SOLID(0.0), CUTOUT(0.5), TRANSLUCENT(0.01), TRIPWIRE(0.1) alpha cutouts.
- TerrainBuilder buffers are per QuadFacing (6 faces + UNDEFINED=5); backFaceCulling routes opaque
  quads into facing buffers, UNDEFINED otherwise. Fluids (vanilla tesselate) always use UNDEFINED.

## Git state
- HEAD is upstream `087ef24`. The working tree has the whole 26.1.2 port as ~108 STAGED but
  NOT COMMITTED changes (no git identity configured in the repo). The user wants a safety
  checkpoint. Set a repo-local identity (`git config user.name/user.email`) and commit before
  making risky changes.