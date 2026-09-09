package net.vulkanmod.shaders;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

public final class PackUniforms {
    private static final Matrix4f IDENTITY = new Matrix4f();
    /*
     * PackUniforms is updated once for every active shader program, not once
     * for every rendered frame.  Keep a frame-scoped snapshot so all passes
     * see the same previous camera state.  Updating these values on every
     * pass would make previousCameraPosition equal the current camera before
     * the composite/TAA passes run, which is observably wrong after movement.
     */
    private static long sampledFrame = Long.MIN_VALUE;
    private static boolean havePreviousFrame;
    private static final Matrix4f lastModelView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static final Matrix4f previousModelView = new Matrix4f();
    private static final Matrix4f previousProjection = new Matrix4f();
    private static final Vector3f lastCamera = new Vector3f();
    private static final Vector3f previousCamera = new Vector3f();
    private static ClientLevel historyLevel;
    private static int historyWidth = -1;
    private static int historyHeight = -1;

    public static void update(PackUniformBuffer uniforms, long legacyPtr, Matrix4f cleanProjection, boolean isQuad) {
        if (uniforms == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = WorldRenderer.getLevel();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        BlockPos cameraBlock = camera.blockPosition();
        long gameTime = level == null ? 0L : level.getLevelData().getGameTime();
        long worldDayTime = level == null ? 6000L : level.getOverworldClockTime();
        // Test-only override keeps sky, lighting and shadow matrices on the
        // same deterministic daylight frame without changing the world clock.
        long debugTime = Long.getLong("vulkanmod.debugTime", -1L);
        long dayTime = debugTime >= 0L ? debugTime : worldDayTime;
        float sunAngle = Math.floorMod(dayTime, 24000L) / 24000.0f;
        float rainStrength = level == null ? 0.0f : level.getRainLevel(1.0f);
        float debugRain = Float.parseFloat(System.getProperty("vulkanmod.debugRain", "-1"));
        if (debugRain >= 0.0f) rainStrength = Math.max(0.0f, Math.min(1.0f, debugRain));
        float width = minecraft.getWindow().getWidth();
        float height = minecraft.getWindow().getHeight();
        float cameraX = (float) camera.position().x;
        float cameraY = (float) camera.position().y;
        float cameraZ = (float) camera.position().z;
        int skyLight = level == null ? 15 : level.getBrightness(LightLayer.SKY, cameraBlock);
        int blockLight = level == null ? 15 : level.getBrightness(LightLayer.BLOCK, cameraBlock);
        Vector3fc forward = camera.forwardVector();

        uniforms.integer("isEyeInWater", camera.getFluidInCamera() == FogType.WATER ? 1 : 0);
        uniforms.integer("frameCounter", (int) Math.floorMod(gameTime, Integer.MAX_VALUE));
        uniforms.integer("worldTime", (int) Math.floorMod(dayTime, 24000L));
        uniforms.integer("worldDay", (int) (dayTime / 24000L));
        // Iris/OptiFine ABI defaults.  PackUniformBuffer only uploads
        // reflected members, so these writes are harmless for programs that
        // do not declare them, while preventing enabled programs from
        // receiving an accidental zero/undefined value after a reload.
        uniforms.integer("moonPhase", 0);
        uniforms.integer("renderStage", -1);
        uniforms.integer("blockEntityId", 0);
        uniforms.integer("entityId", 0);
        uniforms.integer("currentRenderedItemId", 0);
        uniforms.integer("heldItemId", 0);
        uniforms.integer("heldItemId2", 0);
        uniforms.integer("heldBlockLightValue", blockLight);
        uniforms.integer("heldBlockLightValue2", blockLight);
        uniforms.scalar("aspectRatio", height > 0.0f ? width / height : 1.0f);
        uniforms.scalar("eyeAltitude", cameraY);
        uniforms.scalar("frameTime", 1.0f / 60.0f);
        uniforms.scalar("frameTimeCounter", gameTime / 20.0f);
        uniforms.scalar("far", minecraft.options.getEffectiveRenderDistance() * 16.0f);
        uniforms.scalar("near", 0.05f);
        uniforms.scalar("rainStrength", rainStrength);
        uniforms.scalar("screenBrightness", minecraft.options.gamma().get().floatValue());
        uniforms.scalar("viewHeight", height);
        uniforms.scalar("viewWidth", width);
        uniforms.scalar("wetness", rainStrength);
        uniforms.scalar("sunAngle", sunAngle);
        uniforms.scalar("blindness", 0.0f);
        uniforms.scalar("darknessFactor", 0.0f);
        uniforms.scalar("darknessLightFactor", 0.0f);
        uniforms.scalar("maxBlindnessDarkness", 0.0f);
        uniforms.scalar("nightVision", 0.0f);
        uniforms.scalar("cloudHeight", 192.0f);
        if (minecraft.player != null) {
            uniforms.scalar("playerMood", minecraft.player.getCurrentMood());
            uniforms.integer("is_invisible", minecraft.player.isInvisible() ? 1 : 0);
        }
        uniforms.ivec2("atlasSize", VRenderSystem.getTextureSize().getInt(0), VRenderSystem.getTextureSize().getInt(4));
        uniforms.ivec2("eyeBrightness", blockLight * 16, skyLight * 16);
        uniforms.vec3("cameraPosition", cameraX, cameraY, cameraZ);
        uniforms.vec3("previousCameraPosition", cameraX, cameraY, cameraZ);
        uniforms.vec3("eyePosition", cameraX, cameraY, cameraZ);
        uniforms.vec3("relativeEyePosition", 0.0f, 0.0f, 0.0f);
        uniforms.vec3("playerLookVector", forward.x(), forward.y(), forward.z());
        uniforms.vec4("entityColor", 1.0f, 1.0f, 1.0f, 1.0f);
        uniforms.ivec3("cameraPositionInt", (int) Math.floor(cameraX), (int) Math.floor(cameraY), (int) Math.floor(cameraZ));
        uniforms.ivec3("previousCameraPositionInt", (int) Math.floor(cameraX), (int) Math.floor(cameraY), (int) Math.floor(cameraZ));
        uniforms.vec3("cameraPositionFract", fract(cameraX), fract(cameraY), fract(cameraZ));
        uniforms.vec3("previousCameraPositionFract", fract(cameraX), fract(cameraY), fract(cameraZ));
        uniforms.scalar("endFlashIntensityM", 0.0f);
        uniforms.vec3("endFlashPosition", 0.0f, 0.0f, 0.0f);
        uniforms.vec4("lightningBoltPosition", 0.0f, 0.0f, 0.0f, 0.0f);
        uniforms.scalar("starter", 1.0f);
        uniforms.scalar("frameTimeSmooth", 1.0f / 60.0f);
        uniforms.scalar("eyeBrightnessM", skyLight / 15.0f);
        uniforms.scalar("eyeBrightnessM2", skyLight == 15 ? 1.0f : 0.0f);
        uniforms.scalar("rainFactor", rainStrength);
        uniforms.scalar("thunderStrength", level == null ? 0.0f : level.getThunderLevel(1.0f));
        uniforms.scalar("framemod2", (float) Math.floorMod(gameTime, 2L));
        uniforms.scalar("framemod4", (float) Math.floorMod(gameTime, 4L));
        uniforms.scalar("framemod8", (float) Math.floorMod(gameTime, 8L));
        uniforms.scalar("framemod600", (float) Math.floorMod(gameTime, 600L));
        uniforms.scalar("isEyeInCave", 0.0f);
        uniforms.scalar("inDry", 0.0f);
        uniforms.scalar("inRainy", rainStrength > 0.0f ? 1.0f : 0.0f);
        uniforms.scalar("inSnowy", 0.0f);
        uniforms.scalar("velocity", 0.0f);
        uniforms.scalar("inBasaltDeltas", 0.0f);
        uniforms.scalar("inCrimsonForest", 0.0f);
        uniforms.scalar("inNetherWastes", 0.0f);
        uniforms.scalar("inSoulValley", 0.0f);
        uniforms.scalar("inWarpedForest", 0.0f);
        uniforms.scalar("inPaleGarden", 0.0f);

        // The camera probe is populated by vanilla's render-level setup.  The
        // Vulkan path replaces that setup, so it can legitimately still
        // contain the probe default (zero) even though the level has a valid
        // biome/dimension sky color. Read the level's authoritative
        // environment attribute first and retain the camera probe only as a
        // compatibility fallback for older/custom level implementations.
        Integer levelSkyColor = level == null ? null
                : level.environmentAttributes().getValue(
                        net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR,
                        camera.position());
        int skyColor = levelSkyColor != null
                ? levelSkyColor
                : camera.attributeProbe().getValue(
                        net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR, 1.0f);
        uniforms.vec3("skyColor", ((skyColor >>> 16) & 255) / 255.0f,
                ((skyColor >>> 8) & 255) / 255.0f, (skyColor & 255) / 255.0f);

        var fogColor = VRenderSystem.getShaderFogColor().buffer.asFloatBuffer();
        float fogR = fogColor.get(0);
        float fogG = fogColor.get(1);
        float fogB = fogColor.get(2);
        uniforms.vec3("fogColor", fogR, fogG, fogB);

        // Keep the first runtime sky diagnosis tied to the values actually
        // written into the pack UBO.  A valid sky pass with a bad skyColor,
        // fogColor, or sun orbit is visually indistinguishable from a final
        // pass that washed out the image, so counters alone are insufficient.
        if (PackDebug.shouldLogUniforms()) {
            net.vulkanmod.Initializer.LOGGER.info("[packdbg] uniforms time={} sunAngle={} skyColor=({}, {}, {}) fogColor=({}, {}, {}) camera=({}, {}, {})",
                    dayTime, sunAngle,
                    ((skyColor >>> 16) & 255) / 255.0f,
                    ((skyColor >>> 8) & 255) / 255.0f,
                    (skyColor & 255) / 255.0f,
                    fogR, fogG, fogB, cameraX, cameraY, cameraZ);
        }

        Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix().buffer.asFloatBuffer());
        Matrix4f projection = new Matrix4f(VRenderSystem.getProjectionMatrix().buffer.asFloatBuffer());

        /*
         * The pack reconstruction matrices must describe the matrices that
         * actually rasterized the G-buffer.  The old code substituted the
         * clean camera projection here and attempted to derive a bobbed view
         * matrix by multiplying two projection matrices.  That made
         * gbufferProjectionInverse reconstruct a different ray than the one
         * represented by depthtex0, which turns Complementary's fog and
         * atmospheric terms into a screen-wide haze.
         *
         * cleanProjection remains part of the call contract for future Iris
         * previous/clean-matrix support, but current VulkanMod geometry is
         * rasterized with the active matrices below.  Keep this conversion in
         * one helper so the depth convention is testable independently.
         */
        Matrix4f rasterModelView = mv;
        Matrix4f rasterModelViewInverse = new Matrix4f(rasterModelView).invert();
        Matrix4f packProjection = toPackProjection(projection);
        Matrix4f projectionInverse = new Matrix4f(packProjection).invert();

        // A dimension switch or swapchain resize invalidates both the
        // camera history and the projection history.  Treat the next frame
        // as a clean first frame rather than feeding a Nether/old-resolution
        // transform into an overworld composite or TAA pass.
        int frameWidth = Math.round(width);
        int frameHeight = Math.round(height);
        if (historyLevel != level || historyWidth != frameWidth || historyHeight != frameHeight) {
            historyLevel = level;
            historyWidth = frameWidth;
            historyHeight = frameHeight;
            havePreviousFrame = false;
            sampledFrame = Long.MIN_VALUE;
        }

        long frame = PackDebug.frameNumber();
        if (sampledFrame != frame) {
            if (havePreviousFrame) {
                previousModelView.set(lastModelView);
                previousProjection.set(lastProjection);
                previousCamera.set(lastCamera);
            } else {
                // A reload or the first rendered frame has no history.  The
                // correct deterministic value is the current camera, not an
                // identity matrix or an uninitialized buffer.
                previousModelView.set(rasterModelView);
                previousProjection.set(packProjection);
                previousCamera.set(cameraX, cameraY, cameraZ);
                havePreviousFrame = true;
            }
            lastModelView.set(rasterModelView);
            lastProjection.set(packProjection);
            lastCamera.set(cameraX, cameraY, cameraZ);
            sampledFrame = frame;
        }

        // Iris/OptiFine packs use several historical names for the celestial
        // direction.  Complementary derives the same orbit in GetSunVector(),
        // but custom programs often consume these aliases directly.  Publish
        // them in camera space, matching gbufferModelView and the shader's
        // view-ray calculations, without requiring every pack to declare all
        // of them.
        Vector3f sunDirection = PackShadowMatrices.sunDirection(sunAngle);
        Vector3f sunView = new Vector3f(sunDirection).mulDirection(rasterModelView);
        Vector3f upView = new Vector3f(0.0f, 1.0f, 0.0f).mulDirection(rasterModelView).normalize();
        uniforms.vec3("sunPosition", sunView.x, sunView.y, sunView.z);
        uniforms.vec3("sunVector", sunView.x, sunView.y, sunView.z);
        uniforms.vec3("sunDirection", sunView.x, sunView.y, sunView.z);
        uniforms.vec3("shadowLightPosition", sunView.x, sunView.y, sunView.z);
        uniforms.scalar("sunPathRotation", 0.0f);
        uniforms.vec3("moonPosition", -sunView.x, -sunView.y, -sunView.z);
        uniforms.vec3("moonVector", -sunView.x, -sunView.y, -sunView.z);
        uniforms.vec3("upPosition", upView.x, upView.y, upView.z);
        uniforms.vec3("upVector", upView.x, upView.y, upView.z);

        if (PackDebug.shouldLogUniforms()) {
            net.vulkanmod.Initializer.LOGGER.info("[packdbg] orbit rain={} thunder={} sunWorld=({}, {}, {}) sunView=({}, {}, {})",
                    rainStrength,
                    level == null ? 0.0f : level.getThunderLevel(1.0f),
                    sunDirection.x, sunDirection.y, sunDirection.z,
                    sunView.x, sunView.y, sunView.z);
        }

        if (legacyPtr != 0L) {
            if (isQuad) {
                IDENTITY.get(MemoryUtil.memByteBuffer(legacyPtr, 64)); // MVP is identity for full-screen quad
            } else {
                MemoryUtil.memCopy(VRenderSystem.getMVP().ptr, legacyPtr, 64);
            }
            // Vertex shims must see the exact matrices used by the active
            // Vulkan draw.  This is also true for fullscreen passes: their
            // quad shader supplies clip-space positions explicitly, while
            // the legacy slots still need to remain internally consistent.
            rasterModelView.get(MemoryUtil.memByteBuffer(legacyPtr + 64, 64));
            projection.get(MemoryUtil.memByteBuffer(legacyPtr + 128, 64));
            Matrix4f mvInverse = new Matrix4f(mv).invert();
            mvInverse.get(MemoryUtil.memByteBuffer(legacyPtr + 192, 64));
            new Matrix3f(mv).invert().transpose().get3x4(MemoryUtil.memByteBuffer(legacyPtr + 256, 48));
            IDENTITY.get(MemoryUtil.memByteBuffer(legacyPtr + 304, 64)); // texture matrix
            IDENTITY.get(MemoryUtil.memByteBuffer(legacyPtr + 368, 64)); // light texture matrix
            // Keep the legacy fog block ABI valid even when the current
            // vanilla renderer does not expose all four scalar parameters.
            // The color is authoritative; the scalar defaults match the
            // camera projection and disable a second host-side fog curve.
            MemoryUtil.memPutFloat(legacyPtr + 432, fogR);
            MemoryUtil.memPutFloat(legacyPtr + 436, fogG);
            MemoryUtil.memPutFloat(legacyPtr + 440, fogB);
            MemoryUtil.memPutFloat(legacyPtr + 444, 1.0f);
            MemoryUtil.memPutFloat(legacyPtr + 448, 0.05f);
            MemoryUtil.memPutFloat(legacyPtr + 452, minecraft.options.getEffectiveRenderDistance() * 16.0f);
            MemoryUtil.memPutFloat(legacyPtr + 456, 0.0f);
            MemoryUtil.memPutFloat(legacyPtr + 460, 1.0f);
        }

        uniforms.matrix("gbufferModelView", rasterModelView);
        uniforms.matrix("gbufferModelViewInverse", rasterModelViewInverse);
        uniforms.matrix("gbufferProjection", packProjection);
        uniforms.matrix("gbufferProjectionInverse", projectionInverse);
        uniforms.matrix("gbufferPreviousModelView", previousModelView);
        uniforms.matrix("gbufferPreviousProjection", previousProjection);
        // Common Iris aliases.  Different packs use these names for the
        // same camera transform, so expose them together instead of relying
        // on a pack-specific translation rule.
        uniforms.matrix("modelViewMatrix", rasterModelView);
        uniforms.matrix("modelViewMatrixInverse", rasterModelViewInverse);
        uniforms.matrix("projectionMatrix", packProjection);
        uniforms.matrix("projectionMatrixInverse", projectionInverse);
        uniforms.matrix("viewMatrix", rasterModelView);
        uniforms.matrix("viewMatrixInverse", rasterModelViewInverse);
        uniforms.vec3("previousCameraPosition", previousCamera.x, previousCamera.y, previousCamera.z);
        uniforms.ivec3("previousCameraPositionInt", (int) Math.floor(previousCamera.x),
                (int) Math.floor(previousCamera.y), (int) Math.floor(previousCamera.z));
        uniforms.vec3("previousCameraPositionFract", fract(previousCamera.x), fract(previousCamera.y), fract(previousCamera.z));
        PackShadowMatrices.State activeShadow = WorldRenderer.getActiveShadowMatrices();
        writeShadowMatrices(uniforms, activeShadow);
    }

    /** Convert Vulkan's [0,1] depth projection to the OpenGL/Iris [-1,1]
     * clip-space convention used by shader-pack reconstruction code. */
    static Matrix4f toPackProjection(Matrix4f vulkanProjection) {
        return new Matrix4f().m22(2.0f).m32(-1.0f).mul(vulkanProjection);
    }

    /** Update common uniforms while supplying the active light-space matrices. */
    public static void update(PackUniformBuffer uniforms, long legacyPtr, Matrix4f cleanProjection,
                              boolean isQuad, PackShadowMatrices.State shadowState) {
        update(uniforms, legacyPtr, cleanProjection, isQuad);
        if (uniforms == null || shadowState == null) return;
        writeShadowMatrices(uniforms, shadowState);
        uniforms.matrix("vm_shadowRenderProjection", shadowState.renderProjection());
    }

    private static void writeShadowMatrices(PackUniformBuffer uniforms,
                                            PackShadowMatrices.State shadowState) {
        if (shadowState == null) {
            // Bootstrap/menu frames can legitimately precede shadow-map
            // creation. Keep this deterministic, but never use it once the
            // active world has published a light-space state.
            uniforms.matrix("shadowModelView", IDENTITY);
            uniforms.matrix("shadowModelViewInverse", IDENTITY);
            uniforms.matrix("shadowProjection", IDENTITY);
            uniforms.matrix("shadowProjectionInverse", IDENTITY);
            return;
        }
        uniforms.matrix("shadowModelView", shadowState.modelView());
        uniforms.matrix("shadowModelViewInverse", shadowState.modelViewInverse());
        uniforms.matrix("shadowProjection", shadowState.projection());
        uniforms.matrix("shadowProjectionInverse", shadowState.projectionInverse());
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    private PackUniforms() {}
}
