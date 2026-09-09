package net.vulkanmod.shaders;

import net.vulkanmod.shaders.pack.FolderShaderPack;
import net.vulkanmod.shaders.pack.ShaderProperties;
import net.vulkanmod.shaders.transform.*;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.*;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

public class PackCompositeTest {

    @Test
    public void shadowBindingMergePrefersComparisonSampler() {
        ImageDescriptor raw = new ImageDescriptor(24, "sampler2D", "shadowtex0", 18,
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        ImageDescriptor comparison = new ImageDescriptor(24, "sampler2DShadow", "shadowtex0", 18,
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);

        assertTrue(ImageDescriptor.prefer(raw, comparison).isComparisonSampler());
        assertTrue(ImageDescriptor.prefer(comparison, raw).isComparisonSampler());
        assertFalse(ImageDescriptor.prefer(comparison, raw, true).isComparisonSampler());
    }

    @Test
    public void testHighProfileKeepsCoreLightingFeaturesEnabled() {
        ShaderFeatureProfile profile = ShaderFeatureProfile.defaults(ShaderFeatureProfile.Tier.HIGH);
        assertEquals("2", profile.get("SHADOW_QUALITY", "-1"));
        assertEquals("1", profile.get("LIGHTSHAFT_BEHAVIOUR", "0"));
        assertEquals("1", profile.get("RP_MODE", "0"));
    }

    @Test
    public void testComplementaryProgramDiscoveryIncludesGbuffersAndShadow() throws Exception {
        Path packPath = Path.of("/tmp/opencode/cpl");
        if (!Files.isDirectory(packPath)) return;
        try (FolderShaderPack pack = new FolderShaderPack(packPath)) {
            var programs = ShaderProperties.discoveredPrograms(pack, "world0");
            assertTrue(programs.contains("gbuffers_skybasic"));
            assertTrue(programs.contains("gbuffers_skytextured"));
            assertTrue(programs.contains("gbuffers_entities"));
            assertTrue(programs.contains("shadow"));
            assertTrue(programs.contains("gbuffers_weather"));
        }
    }

    @Test
    public void testComplementaryRegistrySeparatesRenderStages() throws Exception {
        Path packPath = Path.of("/tmp/opencode/cpl");
        if (!Files.isDirectory(packPath)) return;
        try (FolderShaderPack pack = new FolderShaderPack(packPath)) {
            ShaderPackConfig config = ShaderPackConfig.load(pack);
            PackProgramRegistry registry = PackProgramRegistry.discover(pack, config, "world0");
            assertTrue(registry.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_skybasic")));
            assertTrue(registry.shadows().stream().anyMatch(p -> p.name().equals("shadow")));
            assertTrue(registry.compute().stream().anyMatch(p -> p.name().equals("shadowcomp")));
            assertNotNull(registry.finalProgram());
        }
    }

    @Test
    public void testPackHighProfileOverridesHostDefaults() throws Exception {
        Path packPath = Path.of("/tmp/opencode/cpl");
        if (!Files.isDirectory(packPath)) return;
        try (FolderShaderPack pack = new FolderShaderPack(packPath)) {
            ShaderPackConfig config = ShaderPackConfig.load(pack);
            assertEquals("3", config.define("BLOCK_REFLECT_QUALITY", "0"));
            assertEquals("2", config.define("WATER_REFLECT_QUALITY", "0"));
            assertEquals("1", config.define("ENTITY_SHADOW", "-1"));
        }
    }

    @Test
    public void testShadowMatricesAreFiniteAndInvertible() {
        var state = PackShadowMatrices.compute(0.25f, 12.5f, 70.0f, -4.25f, 192.0f, 1024);
        assertTrue(state.modelView().isFinite());
        assertTrue(state.projection().isFinite());
        assertTrue(state.modelViewInverse().isFinite());
        assertTrue(state.projectionInverse().isFinite());
        assertEquals(1.0f, state.sunDirection().length(), 0.0001f);
        assertTrue(new org.joml.Matrix4f(state.modelView()).mul(state.modelViewInverse()).isFinite());
    }

    @Test
    public void cameraRelativeShadowBoundsIntersectAtCamera() {
        var state = PackShadowMatrices.compute(0.25f, 12.5f, 70.0f, -4.25f, 192.0f, 1024);
        var bounds = PackShadowMatrices.transformBounds(state,
                -8.0f, -8.0f, -8.0f, 8.0f, 8.0f, 8.0f);
        assertTrue(bounds.intersectsUnitCube());
        assertTrue(bounds.minX() <= 0.0f && bounds.maxX() >= 0.0f);
        assertTrue(bounds.minY() <= 0.0f && bounds.maxY() >= 0.0f);
    }

    @Test
    public void complementarySunOrbitIsFiniteAndDaylightAligned() {
        var noon = PackShadowMatrices.sunDirection(0.25f);
        var evening = PackShadowMatrices.sunDirection(0.75f);
        assertTrue(noon.isFinite());
        assertTrue(evening.isFinite());
        assertEquals(1.0f, noon.length(), 0.0001f);
        assertEquals(1.0f, evening.length(), 0.0001f);
        assertTrue(noon.y > 0.99f);
        assertTrue(evening.y < -0.99f);
    }

    @Test
    public void testShadowDepthConversionRunsAfterPackCompression() throws Exception {
        String source = """
                #version 130
                uniform mat4 shadowProjection;
                uniform mat4 shadowModelView;
                void main() {
                    vec4 position = gl_Vertex;
                    gl_Position = shadowProjection * shadowModelView * position;
                    gl_Position.z *= 0.2;
                    return;
                }
                """;
        var shader = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("shadow.vsh", source), "shadow", "world0", Stage.VERTEX, source));
        String glsl = shader.glsl();
        assertTrue(glsl.contains("#define main vm_shadowMain"));
        assertTrue(glsl.contains("gl_Position = shadowProjection * shadowModelView * vm_glVertex;"));
        assertTrue(glsl.contains("#undef main"));
        assertTrue(glsl.indexOf("vm_shadowMain();") > glsl.indexOf("gl_Position.z *= 0.2;"));
        assertTrue(glsl.contains("gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);"));
        assertFalse(glsl.contains("vm_shadowRenderProjection"));
    }

    @Test
    public void shadowVertexUsesNativeCasterPositionForReconstruction() throws Exception {
        String source = """
                #version 130
                varying vec4 position;
                void main() {
                    position = shadowModelViewInverse * shadowProjectionInverse * ftransform();
                    gl_Position = shadowProjection * shadowModelView * position;
                    gl_Position.z *= 0.2;
                }
                """;
        var shader = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("shadow.vsh", source), "shadow", "world0", Stage.VERTEX, source));
        assertTrue(shader.glsl().contains("position = vm_glVertex;"));
        assertTrue(shader.glsl().contains("gl_Position = shadowProjection * shadowModelView * vm_glVertex;"));
    }

    @Test
    public void testHostShadowLookupUsesRasterizedDepthRange() throws Exception {
        String source = """
                #version 130
                void main() {
                    vec3 shadowPos = vec3(0.0);
                    shadowPos.z *= 0.2;
                    /* DRAWBUFFERS:0 */
                    gl_FragData[0] = vec4(shadowPos, 1.0);
                }
                """;
        var shader = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("gbuffers_terrain.fsh", source),
                "gbuffers_terrain", "world0", Stage.FRAGMENT, source));
        // The rasterizer-side wrapper converts the final compressed clip-Z to
        // Vulkan depth. Sampling must retain Complementary's matching 0.2
        // compression before the texture-space conversion.
        assertTrue(shader.glsl().contains("shadowPos.z *= 0.2;"));
    }

    @Test
    public void imageUniformsRemainStorageResourcesDuringTranslation() throws Exception {
        String source = """
                #version 430
                layout(rgba8) writeonly uniform image2D floodfill_img;
                void main() {
                    imageStore(floodfill_img, ivec2(0), vec4(1.0));
                }
                """;
        var shader = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("composite.fsh", source), "composite", "world0", Stage.FRAGMENT, source));
        assertEquals(1, shader.resources().size());
        assertTrue(shader.resources().get(0).isImage());
        assertTrue(shader.resources().get(0).glslType().equals("image2D"));
        assertTrue(shader.samplers().isEmpty());
    }

    @Test
    public void alphaTestInjectionUsesARealNewline() throws Exception {
        String source = "#version 130\nvoid main() { /* DRAWBUFFERS:0 */ gl_FragData[0] = vec4(1.0); }\n";
        try (MetadataPack pack = new MetadataPack(Map.of(
                "shaders/shaders.properties", "alphaTest.gbuffers_basic=GREATER 0.25\n",
                "shaders/gbuffers_basic.fsh", source),
                List.of("gbuffers_basic.fsh"))) {
            ShaderPackConfig config = ShaderPackConfig.load(pack);
            var shader = new ShaderProcessor(config, false).process(new StageSource(
                    pack, "gbuffers_basic", "world0", Stage.FRAGMENT, null));
            assertTrue(shader.glsl().contains("\nif (!(_fragOut[0].a > 0.25)) discard;"));
            assertFalse(shader.glsl().contains("nif (!"));
        }
    }

    @Test
    public void genericImmediateGbuffersUseEntityVertexContract() throws Exception {
        String source = """
                #version 130
                void main() {
                    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                }
                """;
        var shader = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("gbuffers_block.vsh", source),
                "gbuffers_block", "world0", Stage.VERTEX, source));
        assertTrue(shader.glsl().contains("layout(location = 0) in vec4 vm_EntityVertex"));
        assertTrue(shader.glsl().contains("#define vm_glVertex vm_EntityVertex"));
    }

    @Test
    public void lineGbufferUsesEntityInputsAndLegacyMatrices() throws Exception {
        String source = """
                #version 130
                void main() {
                    vec4 start = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                    vec4 end = projectionMatrix * modelViewMatrix * vec4(vaPosition + vaNormal, 1.0);
                    if (gl_VertexID % 2 == 0) gl_Position = start; else gl_Position = end;
                }
                """;
        var shader = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("gbuffers_line.vsh", source),
                "gbuffers_line", "world0", Stage.VERTEX, source));
        assertTrue(shader.glsl().contains("vm_glProjectionMatrix"));
        assertTrue(shader.glsl().contains("vm_glModelViewMatrix"));
        assertTrue(shader.glsl().contains("#define vm_glVertexID gl_VertexIndex"));
        assertFalse(shader.glsl().contains("vaPosition"));
    }

    @Test
    public void packAlphaTestMetadataInjectsFragmentDiscard() throws Exception {
        String source = """
                #version 130
                void main() {
                    gl_FragColor = vec4(1.0, 1.0, 1.0, 0.2);
                }
                """;
        try (MetadataPack pack = new MetadataPack(Map.of(
                "shaders/shaders.properties", "alphaTest.gbuffers_basic=GREATER 0.25\n",
                "shaders/gbuffers_basic.fsh", source),
                List.of("gbuffers_basic.fsh"))) {
            ShaderPackConfig config = ShaderPackConfig.load(pack);
            ProcessedShader shader = new ShaderProcessor(config, false)
                    .process(new StageSource(pack, "gbuffers_basic", "world0", Stage.FRAGMENT, null));
            assertTrue(shader.glsl().contains("_fragOut[0].a > 0.25"));
            assertTrue(shader.glsl().contains("discard;"));
        }
    }

    @Test
    public void packBlendMetadataDisablesSelectedMrtTargets() throws Exception {
        try (MetadataPack pack = new MetadataPack(Map.of(
                "shaders/shaders.properties", "blend.gbuffers_water.colortex4=off\n"),
                List.of())) {
            ShaderPackConfig config = ShaderPackConfig.load(pack);
            assertTrue(config.blendDisabled("world0", "gbuffers_water", 4));
            assertFalse(config.blendDisabled("world0", "gbuffers_water", 0));
        }
    }

    @Test
    public void testCompressedShadowDepthMatchesSamplingAndReconstruction() {
        var state = PackShadowMatrices.compute(0.25f, 12.5f, 70.0f, -4.25f, 192.0f, 1024);
        var openGlFromVulkan = new org.joml.Matrix4f().m22(2.0f).m32(-1.0f)
                .mul(state.renderProjection());
        for (float z : new float[]{-10.0f, -192.0f, -500.0f}) {
            var point = new org.joml.Vector4f(2.0f, 3.0f, z, 1.0f);
            var packClip = new org.joml.Vector4f(point).mul(state.projection());
            var legacyClip = new org.joml.Vector4f(point).mul(openGlFromVulkan);
            var reconstructed = new org.joml.Vector4f(legacyClip).mul(state.projectionInverse());
            assertEquals(point.z, reconstructed.z, 0.0001f);
            float sampledDepth = (packClip.z / packClip.w * 0.2f) * 0.5f + 0.5f;
            legacyClip.z *= 0.2f;
            float renderedDepth = 0.5f * (legacyClip.z + legacyClip.w) / legacyClip.w;
            assertEquals(sampledDepth, renderedDepth, 0.000001f);
        }
    }

    @Test
    public void packProjectionMatchesTheRasterizedVulkanProjection() {
        var vulkan = new org.joml.Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f,
                0.05f, 512.0f, true);
        var pack = PackUniforms.toPackProjection(vulkan);
        var inverse = new org.joml.Matrix4f(pack).invert();

        for (var point : List.of(
                new org.joml.Vector4f(0.0f, 0.0f, -1.0f, 1.0f),
                new org.joml.Vector4f(2.0f, 1.0f, -32.0f, 1.0f),
                new org.joml.Vector4f(-4.0f, 3.0f, -256.0f, 1.0f))) {
            var vulkanClip = new org.joml.Vector4f(point).mul(vulkan);
            var packClip = new org.joml.Vector4f(point).mul(pack);
            // The XY and W coordinates are unchanged; only clip-space Z is
            // remapped from Vulkan [0,1] to Iris/OpenGL [-1,1].
            assertEquals(vulkanClip.x, packClip.x, 0.00001f);
            assertEquals(vulkanClip.y, packClip.y, 0.00001f);
            assertEquals(vulkanClip.w, packClip.w, 0.00001f);
            assertEquals(2.0f * vulkanClip.z - vulkanClip.w, packClip.z, 0.00001f);
            var reconstructed = new org.joml.Vector4f(packClip).mul(inverse);
            reconstructed.div(reconstructed.w);
            assertEquals(point.x, reconstructed.x, 0.0001f);
            assertEquals(point.y, reconstructed.y, 0.0001f);
            assertEquals(point.z, reconstructed.z, 0.0001f);
        }
    }

    @Test
    public void testDrawBuffersParsing() throws Exception {
        ShaderProcessor processor = new ShaderProcessor(false);

        // Simulated gbuffers_terrain with DRAWBUFFERS:06
        String terrainFsh = """
                #version 130
                /* DRAWBUFFERS:06 */
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(0.5);
                }
                """;
        ProcessedShader psTerrain = processor.process(new StageSource(
                new DummyPack("gbuffers_terrain.fsh", terrainFsh),
                "gbuffers_terrain", "world0", Stage.FRAGMENT, terrainFsh));
        assertArrayEquals(new int[]{0, 6}, psTerrain.drawBuffers());

        // Simulated composite with DRAWBUFFERS:71
        String compFsh = """
                #version 130
                /* DRAWBUFFERS:7 */
                /* DRAWBUFFERS:71 */
                void main() {
                    gl_FragData[0] = vec4(0.2);
                    gl_FragData[1] = vec4(0.8);
                }
                """;
        ProcessedShader psComp = processor.process(new StageSource(
                new DummyPack("composite.fsh", compFsh),
                "composite", "world0", Stage.FRAGMENT, compFsh));
        assertArrayEquals(new int[]{7, 1}, psComp.drawBuffers());

        // Simulated composite4 with DRAWBUFFERS:3
        String comp4Fsh = """
                #version 130
                /* DRAWBUFFERS:3 */
                void main() {
                    gl_FragData[0] = vec4(0.5);
                }
                """;
        ProcessedShader psComp4 = processor.process(new StageSource(
                new DummyPack("composite4.fsh", comp4Fsh),
                "composite4", "world0", Stage.FRAGMENT, comp4Fsh));
        assertArrayEquals(new int[]{3}, psComp4.drawBuffers());
    }

    @Test
    public void shaderPropertiesHonourExplicitOrderAndDimensionEnablement() throws Exception {
        ShaderPack pack = new MetadataPack(
                Map.of(
                        "shaders/shaders.properties",
                        "program.order=final composite composite1 shadow\nshadowDistance=300\n"
                                + "texture.deferred.colortex3=lib/textures/cloud-water.png\n",
                        "shaders/block.properties",
                        "program.composite1.enabled=false\nshadowDistance=96\n"),
                List.of("final.fsh", "composite.fsh", "composite1.fsh", "shadow.fsh"));

        try (pack) {
            List<String> passes = ShaderProperties.passes(pack, "world0");
            assertEquals(List.of("final", "composite", "shadow"), passes);
            ShaderPackConfig config = ShaderPackConfig.load(pack);
            assertEquals("300", config.define("shadowDistance", "192"));
            assertEquals("96", config.define("world0", "shadowDistance", "192"));
            assertEquals("lib/textures/cloud-water.png",
                    config.properties().customTextures("deferred").get("colortex3"));
        }
    }

    @Test
    public void cloudVertexShimUsesNativePositionUvColorContract() throws Exception {
        String source = """
                #version 130
                attribute vec4 gl_Vertex;
                varying vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """;
        ProcessedShader processed = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("gbuffers_clouds.vsh", source),
                "gbuffers_clouds", "world0", Stage.VERTEX, source));
        assertTrue(processed.glsl().contains("vm_CloudVertex"));
        assertTrue(processed.glsl().contains("vm_CloudUV"));
        assertTrue(processed.glsl().contains("vm_CloudColor"));
    }

    @Test
    public void skyVertexShimUsesFullscreenQuadPositionLocation() throws Exception {
        String source = """
                #version 130
                flat out vec3 upVec, sunVec;
                flat out vec4 glColor;
                #ifdef OVERWORLD
                flat out float vanillaStars;
                #endif
                void main() {
                    gl_Position = ftransform();
                    glColor = gl_Color;
                    upVec = vec3(0.0, 1.0, 0.0);
                    sunVec = vec3(0.0, 1.0, 0.0);
                    vanillaStars = 0.0;
                }
                """;
        ProcessedShader processed = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("gbuffers_skybasic.vsh", source),
                "gbuffers_skybasic", "world0", Stage.VERTEX, source));
        assertTrue(processed.glsl().contains("layout(location = 4) in vec4 vm_SkyVertex"));
        assertTrue(processed.glsl().contains("gl_Position = vec4(vm_SkyVertex.xy, 0.0, 1.0)"));
    }

    @Test
    public void waterShimProvidesMaterialAndTangentContracts() throws Exception {
        String waterVsh = """
                #version 130
                attribute vec4 mc_Entity;
                attribute vec4 at_tangent;
                void main() { gl_Position = ftransform(); }
                """;
        ShaderProcessor processor = new ShaderProcessor(false);
        ProcessedShader water = processor.process(new StageSource(
                new DummyPack("gbuffers_water.vsh", waterVsh),
                "gbuffers_water", "world0", Stage.VERTEX, waterVsh));
        assertTrue(water.glsl().contains("layout(location = 0) in vec4 vm_TerrainEntity"));
        assertTrue(water.glsl().contains("#define mc_Entity vm_TerrainEntity"));
        assertTrue(water.glsl().contains("at_tangent vec4(1.0, 0.0, 0.0, 1.0)"));
    }

    @Test
    public void drawBuffersFollowActiveProfileBranch() throws Exception {
        String waterFsh = """
                #version 130
                #define DETAIL_QUALITY 2
                /* DRAWBUFFERS:03 */
                #if DETAIL_QUALITY >= 3
                /* DRAWBUFFERS:03648 */
                #endif
                void main() { gl_FragData[0] = vec4(1.0); }
                """;
        ProcessedShader water = new ShaderProcessor(false).process(new StageSource(
                new DummyPack("gbuffers_water.fsh", waterFsh),
                "gbuffers_water", "world0", Stage.FRAGMENT, waterFsh));
        assertArrayEquals(new int[]{0, 3}, water.drawBuffers());
    }

    @Test
    public void testFullPackPipelinesExistInComplementary() throws Exception {
        Path packPath = Path.of("/tmp/opencode/cpl");
        if (!Files.isDirectory(packPath)) return;

        try (FolderShaderPack pack = new FolderShaderPack(packPath)) {
            // Verify all key programs exist
            assertTrue(pack.exists("shaders/world0/deferred1.fsh") || pack.exists("shaders/deferred1.fsh"));
            assertTrue(pack.exists("shaders/world0/composite.fsh") || pack.exists("shaders/composite.fsh"));
            assertTrue(pack.exists("shaders/world0/composite1.fsh") || pack.exists("shaders/composite1.fsh"));
            assertTrue(pack.exists("shaders/world0/composite4.fsh") || pack.exists("shaders/composite4.fsh"));
            assertTrue(pack.exists("shaders/world0/composite5.fsh") || pack.exists("shaders/composite5.fsh"));
            assertTrue(pack.exists("shaders/world0/composite6.fsh") || pack.exists("shaders/composite6.fsh"));
            assertTrue(pack.exists("shaders/world0/final.fsh") || pack.exists("shaders/final.fsh"));

            // Verify draw buffers parsed from Complementary shaders
            ShaderProcessor processor = new ShaderProcessor(false);
            ProcessedShader fsComp = processor.process(new StageSource(pack, "composite", "world0", Stage.FRAGMENT, null));
            assertNotNull(fsComp.drawBuffers());
            assertTrue(fsComp.drawBuffers().length >= 1);
            assertEquals(7, fsComp.drawBuffers()[0]);

            ProcessedShader fsComp4 = processor.process(new StageSource(pack, "composite4", "world0", Stage.FRAGMENT, null));
            assertNotNull(fsComp4.drawBuffers());
            assertTrue(fsComp4.drawBuffers().length >= 1);
            assertEquals(3, fsComp4.drawBuffers()[0]);

            ProcessedShader fsFinal = processor.process(new StageSource(pack, "final", "world0", Stage.FRAGMENT, null));
            assertNotNull(fsFinal.drawBuffers());
            assertEquals(1, fsFinal.drawBuffers().length);
            assertEquals(0, fsFinal.drawBuffers()[0]);

            // Test compilation of all stages
            String[] passes = {"deferred1", "composite", "composite1", "composite3", "composite4", "composite5", "composite6", "composite7", "final"};
            for (String name : passes) {
                System.out.println("=== START PASS " + name + " ===");
                ShaderProcessor proc = new ShaderProcessor(false);
                ProcessedShader vertex = proc.process(new StageSource(pack, name, "world0", Stage.VERTEX, null));
                ProcessedShader fragment = proc.process(new StageSource(pack, name, "world0", Stage.FRAGMENT, null));
                System.out.println("Pass " + name + " vertex uboSize=" + vertex.uboSize() + " frag uboSize=" + fragment.uboSize());
                var vU = vertex.uniforms().stream().filter(u -> u.kind() == UniformKind.VALUE).map(u -> u.name() + "@" + u.uboOffset()).toList();
                var fU = fragment.uniforms().stream().filter(u -> u.kind() == UniformKind.VALUE).map(u -> u.name() + "@" + u.uboOffset()).toList();
                System.out.println("  Vertex value uniforms: " + vU);
                System.out.println("  Fragment value uniforms: " + fU);
                System.out.println("  EQUAL: " + vU.equals(fU));
                System.out.println("PASS " + name + " drawBuffers=" + java.util.Arrays.toString(fragment.drawBuffers())
                        + " samplers=" + fragment.samplers().stream().map(s -> s.binding() + ":" + s.name()).toList());

                String vInc = "test_pack_" + name + ".vsh";
                String fInc = "test_pack_" + name + ".fsh";
                net.vulkanmod.vulkan.shader.SpirvCompiler.addVirtualInclude(vInc, vertex.glsl());
                net.vulkanmod.vulkan.shader.SpirvCompiler.addVirtualInclude(fInc, fragment.glsl());
                var vertexSpirv = net.vulkanmod.vulkan.shader.SpirvCompiler.compileVirtualShader("test_" + name + ".vsh", vInc, net.vulkanmod.vulkan.shader.SpirvCompiler.ShaderKind.VERTEX_SHADER);
                assertNotNull(vertexSpirv);
                var fragmentSpirv = net.vulkanmod.vulkan.shader.SpirvCompiler.compileVirtualShader("test_" + name + ".fsh", fInc, net.vulkanmod.vulkan.shader.SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
                assertNotNull(fragmentSpirv);
            }
        }
    }

    private static class DummyPack implements ShaderPack {
        private final String path;
        private final String source;

        DummyPack(String path, String source) {
            this.path = path;
            this.source = source;
        }

        @Override public String getName() { return "dummy"; }
        @Override public boolean exists(String p) { return p.endsWith(path); }
        @Override public String getSource(String p) { return p.endsWith(path) ? source : null; }
        @Override public List<String> listFiles(String dir) { return List.of(path); }
        @Override public boolean hasShaders() { return true; }
        @Override public Path getFolder() { return null; }
        @Override public boolean isFolderPack() { return false; }
        @Override public void close() {}
    }

    private static final class MetadataPack implements ShaderPack {
        private final Map<String, String> sources;
        private final List<String> files;

        MetadataPack(Map<String, String> sources, List<String> files) {
            this.sources = sources;
            this.files = files;
        }

        @Override public String getName() { return "metadata"; }
        @Override public boolean exists(String path) { return sources.containsKey(path) || files.contains(path); }
        @Override public String getSource(String path) { return sources.get(path); }
        @Override public List<String> listFiles(String dir) { return files; }
        @Override public boolean hasShaders() { return true; }
        @Override public Path getFolder() { return null; }
        @Override public boolean isFolderPack() { return false; }
        @Override public void close() {}
    }
}
