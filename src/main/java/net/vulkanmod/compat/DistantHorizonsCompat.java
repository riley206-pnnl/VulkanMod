package net.vulkanmod.compat;

import com.mojang.logging.LogUtils;
import net.vulkanmod.vulkan.Renderer;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Handles compatibility, draw hook dispatch, and render pass lifecycle
 * between VulkanMod, Beryl, and Distant Horizons.
 */
public class DistantHorizonsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean INITIALIZED = false;
    private static boolean PRESENT = false;
    private static Object clientApiInstance = null;
    private static MethodHandle renderLodsHandle = null;
    private static MethodHandle renderDeferredLodsHandle = null;
    private static boolean failed = false;

    public static void init() {
        if (INITIALIZED) return;
        INITIALIZED = true;

        try {
            Class<?> clientApiClass = Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi");
            Field instanceField = clientApiClass.getField("INSTANCE");
            clientApiInstance = instanceField.get(null);

            Method renderLodsMethod = clientApiClass.getMethod("renderLods");
            Method renderDeferredMethod = clientApiClass.getMethod("renderDeferredLodsForShaders");

            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            renderLodsHandle = lookup.unreflect(renderLodsMethod);
            renderDeferredLodsHandle = lookup.unreflect(renderDeferredMethod);

            PRESENT = true;
            LOGGER.info("[VulkanMod] Distant Horizons detected and hooked successfully.");
        } catch (ClassNotFoundException e) {
            PRESENT = false;
            LOGGER.info("[VulkanMod] Distant Horizons not present.");
        } catch (Throwable t) {
            PRESENT = false;
            LOGGER.warn("[VulkanMod] Failed to initialize Distant Horizons hooks: {}", t.getMessage());
        }
    }

    public static boolean isPresent() {
        if (!INITIALIZED) init();
        return PRESENT;
    }

    public static void renderOpaqueLods() {
        if (!isPresent() || failed) return;

        try {
            // Suspend the active render pass so DH can record its offscreen passes
            Renderer.getInstance().endRenderPass();

            renderLodsHandle.invoke(clientApiInstance);
        } catch (Throwable t) {
            LOGGER.error("[VulkanMod] Error during Distant Horizons opaque LOD render", t);
            failed = true;
        } finally {
            // Rebind the active target pass (handles both vanilla MainPass and Beryl ShaderMainPass)
            Renderer.getInstance().getMainPass().rebindMainTarget();
        }
    }

    public static void renderTranslucentLods() {
        if (!isPresent() || failed) return;

        try {
            Renderer.getInstance().endRenderPass();

            renderDeferredLodsHandle.invoke(clientApiInstance);
        } catch (Throwable t) {
            LOGGER.error("[VulkanMod] Error during Distant Horizons translucent LOD render", t);
            failed = true;
        } finally {
            Renderer.getInstance().getMainPass().rebindMainTarget();
        }
    }
}
