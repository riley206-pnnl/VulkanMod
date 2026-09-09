# Beryl pipeline polish — 2026-09-08

## Scope

Native Beryl (`src/beryl`) on NeoForge 26.1.2. Existing working-tree changes were retained. No changes were made to the Iris/OptiFine transpiler. Two core chunk-renderer logging guards were necessary because their diagnostics ran every frame while the inactive pack debug counter remained at zero.

## Changes

1. **Frame pacing and resources**
   - Reuse Beryl light vectors, light matrices, shadow pose stack and blit projection. Existing mapped uniform buffers remain persistent. This does not make the entire renderer allocation-free.
   - Retain deterministic shadow section scanning, independent shadow queues and all caster faces. Skip the absent SOLID layer when opaque layers are merged. Preserve per-area buffer binding before UBO upload and non-global UBO 2.
   - Keep sections dirty when the dispatcher rejects a shadow rebuild job.
   - Make shadow diagnostics opt-in (`-Dberyl.debugShadows=true`) and rate-limited; guard core chunk draw/rebuild diagnostics with `vulkanmod.debugChunkReadiness`.
   - Correct bloom fragment-read/compute-write and upsample read-modify-write dependencies; retain required inter-dispatch dependencies.
   - Bound bloom invocations to image dimensions, clamp mip dimensions to at least one pixel, and fix the vertical downsample kernel offset.
   - Release old bloom images/passes on resize and previously omitted render passes/pipelines on cleanup. Skip bloom dispatches when intensity is zero.
2. **Settings**
   - Label the existing registered entry “Beryl Shaders”, preserving its icon, option pages and NeoForge config-screen entry point.
   - Queue one resource reload for shader define/resolution changes at the next world frame, retaining the initialized state until old resources can be cleaned up.
   - Shadow distance, absorption and bloom continue to update from config each frame. Apply writes `config/beryl_settings.json` through the settings registry callback.
   - Correct pipeline redirection bookkeeping so a null original pipeline is not confused with an unsaved original pipeline.
3. **Hand and items**
   - Flush deferred hand submissions with the matching camera rotation before screen effects/post-processing; retain separate cleared hand depth and use a Vulkan [0,1] depth projection.
   - Normalize interpolated normals for entity GGX lighting.
   - Isolate first-person item batches from the arm and other hand and provide material presets for vanilla iron, gold, copper, diamond and netherite sprites.
   - These are built-in whole-item presets inferred from the first quad's sprite. Unknown/custom sprites use a rough dielectric; mixed-material textures do not have per-pixel material masks. Arm geometry keeps the default dielectric material.
4. **Water and SSR**
   - Fix the pre-existing location collision: Material occupies 7–12; water depth now uses 13 in both stages. Declare/bind WaterAbsorption consistently in native and JSON UBO configurations.
   - Use shared world-space wave phases and negative height-gradient normals, preserving vertical water-face orientation.
   - Replace the old off-screen-hit SSR path with at most 48 view-space steps and six intersection refinements. Clip toward-camera rays, reject sky/out-of-bounds samples and fade screen edges/distant hits.
   - Restore attachment access dependencies after copying the opaque color/depth snapshot.
   - SSR still cannot reflect objects outside the screen or missing from the opaque snapshot; those rays use sky lighting.
5. **Weather**
   - Preserve rain/snow routing through the HDR particle shader before bloom.
   - Darken storm sky and ambient lighting and smoothly interpolate night sky contribution. Preserve cloud voxel opacity, face shading and restrained direct lighting.
   - Add rain-driven roughness/albedo changes on sky-exposed upward terrain. Exposure is approximated from skylight, not a per-block precipitation test.

## Verification performed

- Each phase passed `./gradlew compileBerylJava berylJar assemble` before the next phase.
- `./gradlew test --tests net.beryl.BerylShaderCompileTest` compiles all 44 native shader stages across 16 combinations of SSR, water waving, colored shadows and textured sun: **704 successful stage compilations**.
- Shader resources are declared as Gradle test inputs so asset edits invalidate the test result.
- Development client launched with `runClient -Pvulkanmod.noEarlyDisplay=true -Pquickplay='New World (4)'`. World/terrain, player shadow, sky and first-person arm rendered on NVIDIA RTX 4070 / 595.84 without a Beryl shader compilation exception or device loss during the observed smoke test.
- That smoke run used 854×480, SSR disabled and 3072 shadows. It is not a performance benchmark. The user took control of the window; further UI input stopped. Final logging/resize/redirection fixes were compiled after that launch and require restart.
- Existing optional-mod warnings and an Apotheosis data error were present in the development log; they did not prevent world entry.

## Remaining in-game acceptance checks

- After restarting, open Video Settings and confirm Beryl Shaders, then apply/reopen 1024/2048/4096 shadows, distance, SSR, waving water and bloom. Confirm saved values and repeated reload/resize stability.
- Inspect both hands with metal tools, block items, skin, glint, nearby walls, day/night and tree shadows.
- Compare river/ocean shore reflections and wave seams, including screen-edge and toward-camera reflection rays.
- Compare clear/rain/thunder and snowy precipitation, checking cloud opacity, fog and wet surfaces.
- Measure a repeatable traversal at 1920×1080 and 2560×1440 after chunk warm-up, recording median/p99 frame times and 1% lows. The 144+ FPS/no-micro-stutter target is **not yet established**.
