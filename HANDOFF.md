# Handoff: VulkanMod (Minecraft 26.1.2 / NeoForge) & Beryl Shader Pipeline

## 1. System & Architecture Overview

- **Repository**: `/home/riley/dev/minecraft_dev/VulkanMod`
- **Active Branch**: `neoforge-26.1.2` (Target: Minecraft 26.1.2 / NeoForge)
- **Active Hardware Profile**: NVIDIA GeForce RTX 4070, Driver 595.84 on Linux
- **Deployment Location**: `/home/riley/.minecraft/profiles/vulkanmod/mods/`
- **Output Artifacts**:
  1. `VulkanMod_26.1.2-0.6.8+26.1.2.jar` (Core Vulkan renderer)
  2. `Beryl-NeoForge-0.2.1-alpha-local.jar` (Active companion mod providing the HDR shader pipeline)

### Active Shader Path vs. Transpiler Path
- **Active / In-Game Shaders**: The live shader pipeline is the **Beryl built-in shader system** (`src/beryl`), which interfaces directly with VulkanMod through native Vulkan pipelines, custom UBOs, and compute shaders (bloom, shadow mapping, atmospheric scattering, PBR materials, procedural skybox, and 3D volumetric clouds).
- **Secondary Effort (Transpiler)**: A transpiler for external Iris/OptiFine packs (`net.vulkanmod.shaders.*`) exists in `src/main`. Phase 1 (SPIR-V transpilation) compiles 31/31 passes for Complementary Reimagined r5.8.1, but in-game runtime routing is secondary to the native Beryl pipeline.

---

## 2. Completed Bug Fixes & Milestones

### 1. Concurrency Crash in Material Map
- **Issue**: `ArrayIndexOutOfBoundsException` in Fastutil's `Object2IntOpenHashMap` during world loading / `allChanged()`.
- **Fix**: Replaced lazy runtime mapping with eager static thread-safe initialization (`initBlockIdMap()`) guarded by `volatile boolean initialized` during mod initialization.

### 2. Texture Binding Overwrites & Shadow Samplers
- **Issue**: Fallback `whiteTexture` (`R8G8B8A8_UNORM`) overwritten manual sampler slots 3, 4, 5. `sampler2DShadow` caused `IllegalStateException`.
- **Fix**:
  - Registered Beryl's samplers (`"ShadowMap"`, `"ShadowMap1"`, `"Framebuffer"`, `"ShadowColor"`, `"Depthbuffer"`) in `VTextureSelector`.
  - Added dedicated depth fallback (`whiteDepthTexture` with `D32_SFLOAT`).
  - Added `"sampler2DShadow"` mapping to `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER` in `PipelineConfig`.

### 3. GPU Crash / Device Lost (`VK_ERROR_DEVICE_LOST` / NVIDIA Xid 69)
- **Issue**: In `ShaderRendererResources.java`, vertex element IDs were misaligned (`ELEMENT_NORMAL = 4`, `ELEMENT_BLOCK_ID_INT = 5`), conflicting with `UV2` (`id = 4`) and `NORMAL` (`id = 5`). The hardware vertex fetcher encountered format mismatches (`VK_FORMAT_UNDEFINED` and float-vs-int mismatch), triggering an instant GPU crash (`NVRM: Xid 69`).
- **Fix**: Corrected element IDs (`UV2 = 4`, `NORMAL = 5`, `BLOCK_ID = 7`) and added fail-fast validation in `GraphicsPipeline.java` to prevent zero/undefined formats.

### 4. Solid Blocks Missing Sunlight & Shadows (Zero Normals)
- **Issue**: Foliage casted and received shadows, but solid blocks (dirt, stone, grass blocks) were completely dark with zero shadow casting.
- **Fix**: NeoForge 26.1.2 uses the 12-argument `BakedQuad` constructor. Injected into the 12-argument constructor in `BakedQuadM.java` to compute packed normals, added fallback normal computation in `NormalHelper.java`, and fixed quad diagonal indexing for stride-64 layouts.

### 5. Water Completely Invisible / Clear
- **Issue**: Fluid rendering stubs in `ExtTerrainBuilder.java` were empty, causing water vertices to have 0 color, 0 UVs, and 0 light, bypassing `water.glsl`.
- **Fix**: Implemented full `position()`, `color()`, `uv()`, `light()`, and `normal()` writers in `ExtTerrainBuilder`, hooked `setFluidBlockAttributes` in `FluidRenderer`, and tuned water opacity and Fresnel curves in `water.glsl`.

### 6. Tree & Terrain Shadow Flickering
- **Issue**: Backface culling in `DrawBuffers` against a moving light position, BFS cave-culling in `ShadowMapSectionGraph`, and main camera queue resets caused shadows to flicker and disappear.
- **Fix**: Added dedicated `shadowSectionQueue` to `ChunkArea`, switched to deterministic frustum scanning over non-empty chunk sections, disabled backface culling during shadow pass, and added a minimum bias floor for foliage.

### 7. Terrain & Trees Not Casting Shadows onto Ground
- **Issue**: `ShadowMap.renderLayer` called `renderer.uploadAndBindUBOs(pipeline)` before `bindBuffers()`, permanently triggering `setUseGlobalBuffer(true)` on UBO2. All section offsets collapsed to (0, 0, 0).
- **Fix**: Explicitly set `pipeline.getUBO(2).setUseGlobalBuffer(false)`, removed premature UBO upload, and aligned allowed render types with `TerrainRenderType.SEMI_COMPACT_RENDER_TYPES`.

### 8. Night Water Visibility & Moonlight Specular
- **Issue**: At night, `light.z` dropped to zero, zeroing out sky reflections and diffuse lighting. Inverted light vector produced 0 moonlight illumination.
- **Fix**: Added moonlight vector switching when `NightFactor > 0.4`, replaced direct light multiplier with sky access modulation, added an ambient floor, and updated night sky color with deep nocturnal blue.

### 9. Blinding Cloud Brightness & Bloom Blowout
- **Issue**: Cloud diffuse lighting used raw `LightColor` (magnitude ~8.0), reaching 4.0 - 6.0+ HDR brightness and triggering blinding nuclear bloom. Vertex color shading was discarded while `ColorModulator` was multiplied twice.
- **Fix**: Scaled direct sunlight to natural levels (`LightColor * 0.14 * directFactor`), preserved vertex color face shading, added sky/fog ambient illumination, and added soft moonlight ambient at night.

### 10. Skybox Misalignment & Octagonal Dome Distortion
- **Issue**: `SkyRenderer2.renderSkyDisc` applied `RenderSystem.getModelViewMatrix()`, which is not synchronized with camera orientation in NeoForge 26.1.2. The sky dome did not rotate with the camera, corrupting view-space coordinates and sunset gradients. In addition, the dome was tessellated into only 8 polygonal slices (45° steps).
- **Fix**: Replaced modelview matrix with `RenderingPipeline.view` (pure camera rotation), disabled culling on the dome, and increased dome tessellation from 8 to 36 radial slices (10° steps).

### 11. See-Through Ghost Clouds
- **Issue**: Upstream Beryl shader bug: `color.a *= 1.0 - linear_fog_value(vertexDistance, 0, FogCloudsEnd)`. Fog started at distance 0 and `FogCloudsEnd` defaulted to 1.0, crushing cloud alpha to 0 for any clouds at vertical distance > 120 blocks.
- **Fix**: Made clouds 100% solid and opaque overhead (`color.a = vertexColor.a`), moving the distance fog fade exclusively to the far horizon edge (`fadeEnd = max(FogEnd * 1.5, 384.0)`). Set `ColorModulator.a = 1.0F` in `BerylCloudRenderer.java`.

### 12. Startup Crash on Shader Compile (`translucent.vsh`)
- **Issue**: Experimental `layout(location = 8) out float vWaterDepth` conflicted with `layout(location = 7) out flat Material material`. In GLSL, structs with 6 members span locations 7 through 12, causing location 8 to overlap and crash SPIR-V compilation.
- **Fix**: Removed the conflicting attribute and restored clean locations across `translucent.vsh`, `translucent.fsh`, and `water.glsl`.

---

## 3. Build, Run, and Deployment Instructions

### Build & Deploy Command
```bash
./gradlew compileBerylJava berylJar assemble && \
cp -f build/libs/Beryl-NeoForge-0.2.1-alpha-local.jar /home/riley/.minecraft/profiles/vulkanmod/mods/
```

### Core Mod Build & Deploy Command (When modifying `src/main`)
```bash
./gradlew assemble && \
cp -f build/libs/VulkanMod_26.1.2-0.6.8+26.1.2.jar /home/riley/.minecraft/profiles/vulkanmod/mods/
```

### Hotkeys & In-Game Controls
- **Key 'R'**: Live toggle shaders ON / OFF at runtime.
- **Configuration**: Settings stored in `/home/riley/.minecraft/profiles/vulkanmod/config/beryl_settings.json`.

---

## 4. Current Status & Next Steps

- [x] Base VulkanMod NeoForge 26.1.2 engine stable.
- [x] Beryl companion shader mod compiling, loading, and fully rendering in-game.
- [x] Shadows working on entities, blocks, and trees without popping or acne.
- [x] Water rendering with Fresnel, reflections, and nighttime visibility.
- [x] Clouds rendering with solid 3D voxel geometry, face shading, and balanced bloom.
- [x] Skybox rendering smooth continuous circular dome synchronized with camera rotation.
- [ ] Monitor player feedback on water wave distortion / SSR quality.
- [ ] Investigate VulkanMod shutdown SIGSEGV (`vkDestroySwapchainKHR` on exit).