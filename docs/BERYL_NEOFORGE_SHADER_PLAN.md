# Beryl reference review and NeoForge shader plan

Reviewed 2026-09-07 against the current working tree, including uncommitted shader work.

## Recommendation

Prioritize a Beryl-compatible native shader mode on NeoForge as the first visible milestone. Preserve the Iris/OptiFine pack implementation as an experimental, mutually exclusive mode. Establish a working native rendering baseline before expanding pack compatibility.

Beryl does not solve Iris compatibility: it owns an integrated shader pack and a fixed Vulkan rendering pipeline. Porting its loader integration can deliver Beryl's visual effects; it will not make Complementary or arbitrary Iris packs work. The loader changes appear relatively small, but integration with our modified renderer still needs a compatibility audit and runtime validation.

## Evidence and limitations

The supplied folder is an extracted compiled mod, not an original source checkout. I inspected its metadata, GLSL/JSON assets, and Java classes decompiled with the locally installed Vineflower. Temporary inspection output is under `/tmp/beryl-review/beryl`; no decompiled implementation was added to this repository. Decompiled control flow and signatures should be checked against bytecode when implementing delicate mixins.

Its fabric.mod.json declares Beryl 0.2.1-alpha, Minecraft >=26.1, and VulkanMod >=0.6.7. Our gradle.properties targets Minecraft 26.1.2, NeoForge 26.1.2.100, VulkanMod 0.6.8+26.1.2, with Java 25 in build.gradle. These are promising version matches, not proof of binary compatibility.

The author's page explicitly describes an integrated shader pack and lists All Rights Reserved: https://modrinth.com/mod/beryl. Obtain the author's source and redistribution terms before shipping copied Beryl classes/assets. An independently implemented native pipeline informed by the observed architecture remains a separate implementation option; do not assume VulkanMod's license also covers Beryl.

This was a static review, not a successful Beryl/NeoForge launch. Existing local test reports show 15 terrain and 26 composite tests passing, but I did not rerun them. The latest log contains test-worker varying-interface warnings, not evidence of successful in-game rendering. No single GPU-failure root cause is established by this review.

## Comparison

| Area | Beryl reference | Current fork | Consequence |
|---|---|---|---|
| Shader source | Bundled Vulkan-oriented shaders and pipeline JSON | External shaderpacks, properties, GLSL translation | Native shaders avoid the largest compatibility surface |
| Loader integration | Fabric imports confined to BerylMod in inspected classes | NeoForge initialization and packaging already present | Replace entrypoint/config/version/key registration; retain renderer logic where compatible |
| Frame ownership | ShaderMainPass tracks current framebuffer and uses offscreen HDR/final targets before presentation | PackMainPass alternates G-buffer and swapchain with a GUI-phase flag | One selected backend must own targets and presentation |
| World staging | RenderingPipeline begins shadows, restores camera state, then binds HDR world target; copies scene/depth for SSR at a main-pass boundary | Hooks distributed across WorldRenderer, LevelRendererMixin, PipelineManager, and pack classes | Centralize stage transitions and document their inputs/outputs |
| Geometry | Extended compressed terrain format includes normal and material ID, with matching builders | Custom terrain stream plus shader translation and special cases | Preserve matching CPU layout, Vulkan attributes, and shader inputs as a unit |
| Shadows | Separate ShadowMap and ShadowMapSectionGraph; explicit render stage and target overrides | PackShadowPipeline and terrain/entity shadow hooks | Validate independent light-space visibility and restoration of camera/render state |
| Color | Explicit sRGB texture view selection, gamma handling, HDR targets, final conversion | Multiple pack formats and direct swapchain presentation | Verify linear lighting and exactly one intended display conversion |
| Post effects | Fixed bloom, reflection inputs, final shader | Discovered deferred/composite/final programs | A fixed native chain is a much smaller first milestone |

## Concrete findings in our code

1. **Deferred timing needs correction for pack compatibility.** PackCompositePipeline.render runs both deferredPasses and compositePasses together; LevelRendererMixin calls it at LevelRenderer.renderLevel RETURN. The pack scheduler needs separate execution points so deferred work can run after opaque rendering and before translucency when required. A program's name being discovered does not establish correct execution semantics.
2. **Opaque depth is copied at the terrain boundary.** LevelRendererMixin copies depthtex0 to depthtex1 immediately after opaque terrain. Check the actual entity/block-entity ordering and take the opaque snapshot after all relevant opaque contributors, before translucent rendering. Beryl's copy hook follows an endBatch call, illustrating why the exact boundary matters.
3. **Frame ownership is fragile.** PackMainPass begins world frames on the swapchain to avoid a native sky/HDR format mismatch, but world rebinds select a separate G-buffer target. Pipeline formats, target getters, render-pass bindings, and world/GUI transitions need one authoritative stage state. Its startup GUI comment also disagrees with the initial false flag; investigate lifecycle behavior rather than treating the comment as evidence.
4. **Vertex semantics remain incomplete.** ShaderProcessor substitutes a constant +X tangent for gbuffers_water and zero values for selected entity-shadow attributes. These substitutions may compile while producing incorrect normal mapping/material behavior. Supply real data for supported features and reject unsupported requirements explicitly.
5. **Partial activation hides failures.** PackCompositePipeline.init logs and skips failed deferred/composite programs, then can mark itself active if another composite or final program exists. Build a complete candidate pipeline and activate atomically; report required-pass failures visibly instead of presenting a partially functioning pack as ready.
6. **Feature advertisement exceeds demonstrated support.** ShaderFeatureProfile contains Complementary-oriented defaults and permits Ultra world-space reflections while its comment says supporting image/SSBO infrastructure is pending. PackProgramRegistry discovers compute programs, but discovery is not dispatch. Define backend capabilities and validate the selected profile against actual implementations.

These are source-level findings and risks, not claims that each caused the user's current visual symptoms.

## Implementation sequence

### 1. Preserve the current work and establish a baseline

- Snapshot tracked and untracked source changes before implementation; the working tree contains substantial unfinished work. Do not reset it or mix unrelated queue/upload fixes into the shader port.
- Record a shaders-off NeoForge launch, fixed test world/camera, screenshots, and validation output.
- Where practical, run this exact Beryl artifact with its matching Fabric VulkanMod version in an isolated instance as a visual reference. Verify the reference actually works on this machine before treating it as a known-good runtime.
- Record artifact hashes, versions, settings, and pack versions for reproducibility.

Exit: reproducible native-off baseline and either a captured Beryl reference or an explicitly documented inability to run it.

### 2. Introduce one shader backend owner

- Add an explicit selection such as OFF, NATIVE, and IRIS_EXPERIMENTAL.
- The selected backend alone controls main-pass installation, terrain shader getter, vertex builder/format, per-frame stage callbacks, resize/reload, and cleanup.
- Audit PipelineManager.ensurePackMainPassForFrame and initialization/reload logic so they cannot reinstall PackMainPass over the native backend.
- Switch at a safe frame boundary, retire GPU resources after their last use, and rebuild chunks when the vertex format changes.
- Preserve the existing pack implementation behind its experimental selection.

Exit: repeated switches and world reloads without stale pipeline state or terrain-layout mismatches.

### 3. Adapt Beryl integration and audit every hook

For an authorized Beryl port, prefer a separate NeoForge companion module with a narrow backend integration API in VulkanMod. For an independent implementation, keep the same backend boundary but supply original native shaders and rendering code.

- Replace ClientModInitializer with NeoForge client initialization; replace FabricLoader config/version access and Fabric KeyMappingHelper with the matching NeoForge APIs. Confirm exact APIs against the locally resolved NeoForge sources during implementation.
- Replace fabric.mod.json and the nested Fabric key-mapping dependency with NeoForge metadata/dependencies and mixin registration.
- Beryl's three backend-field access wideners are already represented in our access transformer; verify names/signatures rather than duplicating them.
- Verify all registered mixin targets against our fork and transformed Minecraft classes, particularly GameRenderer, LevelRenderer lambda injection points, RenderPipeline, Renderer, WorldRenderer, fluid/vertex builders, image creation, and descriptor image views.
- Existing extension points include setTerrainVertexFormat, setShaderGetter, setTerrainBuilderConstructor, setMainPass, addOnAllChangedCallback, and getImageView(int). Check descriptors and behavioral expectations, not only method names.
- Review Beryl's mutable-format/sRGB-view handling against our VulkanImage implementation and descriptor caching. Avoid duplicate or incompatible injections.

Exit: a NeoForge launch with all required mixins applied and the native backend selectable, initially using a simple scene shader.

### 4. Bring up native rendering in visible increments

1. Textured opaque terrain and entities into an explicit HDR target, then a simple final blit and correct GUI rendering.
2. Correct normals/material IDs and directional lighting; validate compressed/uncompressed position offsets and lightmap values.
3. Terrain and entity shadows with stable camera-relative/light-space matrices and separate shadow visibility.
4. Translucent water/glass using a completed opaque color/depth snapshot; then sky, clouds, particles, weather, hand, and special entity pipelines.
5. Bloom, reflections, and final tone/color conversion after the base chain is correct.

At each increment capture the relevant intermediate target, not just the final screenshot. Check viewport, depth convention, target format, descriptor layout, image barriers, and blending. Add feature toggles for isolating failures rather than silently disabling failed passes.

Exit: the native shader mode works across the runtime matrix below, with no shader-attributable validation errors and no unexplained missing scene categories.

### 5. Repair Iris compatibility using the stable backend

- Keep pack loading/properties, program discovery, uniform machinery, and diagnostics where they remain useful.
- Express pack programs as stages on the validated backend: shadow, opaque G-buffer, opaque snapshot/deferred boundary, translucent work, hand as required, composite, final, GUI/presentation.
- Make per-pass attachments, formats, clear/load rules, read/write texture versions, history persistence, and sampler types explicit.
- Fix interface validation and real vertex attributes before adding more translation special cases.
- Gate compute/storage-image/SSBO/history features by implemented capabilities; discovery alone must not enable them.
- First validate a tiny controlled pack. Then support one pinned real pack/profile, adding only the semantics it demonstrably requires. Avoid claiming arbitrary Iris support.

Exit: a named pack/version/profile renders correctly across the same runtime matrix, and unsupported profiles fail with actionable reasons.

## Runtime validation matrix

Use fixed time/weather/camera captures for day/night shadows; water above and below the surface; glass overlap; moving entities and block entities; held items; sky/clouds/rain; GUI/inventory/tooltips; and screenshots. Exercise resize/fullscreen, resource reload, shader toggles, world leave/rejoin, and Overworld/Nether/End transitions. Include an offscreen shadow caster to distinguish camera visibility from shadow visibility.

Run compile and focused tests for changed contracts, plus Vulkan validation in the actual client. Record frame time and GPU memory against the same scene, without inventing a performance target before baseline measurements. Compilation and SPIR-V success are necessary but do not establish visual correctness.

## First implementation milestone

A selectable native shader backend on NeoForge that renders textured terrain and entities through an HDR scene target to the screen, with a correct GUI and safe toggling. This isolates renderer integration from Iris translation and provides a concrete foundation for shadows and the remaining effects.
