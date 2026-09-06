package net.vulkanmod.mixin.window;

import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.option.Options;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.*;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Final @Shadow private long handle;

    @Shadow private boolean vsync;
    @Shadow private boolean fullscreen;

    @Shadow @Final private static Logger LOGGER;

    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Shadow public abstract int getWidth();

    @Shadow public abstract int getHeight();

    @Unique private boolean wasOnFullscreen = false;

    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void vulkanHint(int width, int height, String title, long monitor, com.mojang.blaze3d.systems.GpuBackend backend, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Long> cir) {
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        //Fix Gnome Client-Side Decorators
        boolean b = (Platform.isGnome() | Platform.isWeston() | Platform.isGeneric()) && Platform.isWayLand();
        GLFW.glfwWindowHint(GLFW_DECORATED, (b ? GLFW_FALSE : GLFW_TRUE));
    }

    @Redirect(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/EarlyLoadingScreenController;takeOverGlfwWindow()J"))
    private static long vulkanHandoff(EarlyLoadingScreenController controller) {
        long handle = controller.takeOverGlfwWindow();

        if (GLFW.glfwGetWindowAttrib(handle, GLFW_CLIENT_API) != GLFW_NO_API) {
            LOGGER.warn("VulkanMod: NeoForge early loading window has an OpenGL context, handing off to a fresh contextless Vulkan window.");

            int[] width = new int[1];
            int[] height = new int[1];
            GLFW.glfwGetWindowSize(handle, width, height);

            // 1. Terminate any background renderScheduler in the early window immediately
            try {
                java.lang.reflect.Field schedulerField = controller.getClass().getDeclaredField("renderScheduler");
                schedulerField.setAccessible(true);
                java.util.concurrent.ScheduledExecutorService scheduler = (java.util.concurrent.ScheduledExecutorService) schedulerField.get(controller);
                if (scheduler != null) {
                    scheduler.shutdownNow();
                    scheduler.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (Throwable t) {
                LOGGER.debug("VulkanMod: Failed to shut down earlydisplay renderScheduler: {}", t.getMessage());
            }

            // 2. Release the loading renderer while its OpenGL window still exists.
            try {
                GLFW.glfwMakeContextCurrent(handle);
                org.lwjgl.opengl.GL.createCapabilities();
                java.lang.reflect.Method closeMethod = controller.getClass().getMethod("close");
                closeMethod.invoke(controller);
            } catch (Throwable t) {
                LOGGER.debug("VulkanMod: Failed to close early display window: {}", t.getMessage());
            }
            GLFW.glfwMakeContextCurrent(0L);
            org.lwjgl.opengl.GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(handle);
            net.vulkanmod.compat.EarlyWindowCompat.setHandoffComplete(true);
            net.vulkanmod.compat.EarlyWindowCompat.disableFmlEarlyWindowProvider();
            disableEarlyWindowTick();

            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            boolean decorated = !((Platform.isGnome() | Platform.isWeston() | Platform.isGeneric()) && Platform.isWayLand());
            GLFW.glfwWindowHint(GLFW_DECORATED, (decorated ? GLFW_TRUE : GLFW_FALSE));
            long freshWindow = GLFW.glfwCreateWindow(width[0], height[0], "", 0L, 0L);
            if (freshWindow == 0L) {
                throw new RuntimeException("VulkanMod: Failed to create a fresh contextless Vulkan window during NeoForge early window handoff");
            }
            return freshWindow;
        }

        return handle;
    }

    @Unique
    private static void disableEarlyWindowTick() {
        try {
            Class<?> clazz = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler");
            java.lang.reflect.Field providerField = clazz.getDeclaredField("provider");
            providerField.setAccessible(true);
            providerField.set(null, null);
        } catch (Throwable t) {
            LOGGER.warn("VulkanMod: Failed to neutralize NeoForge early window tick: {}", t.getMessage());
        }

        try {
            Class<?> cml = Class.forName("net.neoforged.neoforge.client.loading.ClientModLoader");
            java.lang.reflect.Field elsField = cml.getDeclaredField("earlyLoadingScreen");
            elsField.setAccessible(true);
            elsField.set(null, null);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void getHandle(CallbackInfo ci) {
        VRenderSystem.setWindow(this.handle);
    }

    /**
     * @author
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        this.vsync = vsync;
        Vulkan.setVsync(vsync);
    }

    /**
     * @author
     */
    @Overwrite
    public void toggleFullScreen() {
        this.fullscreen = !this.fullscreen;
        Options.fullscreenDirty = true;

        if (!this.fullscreen) {
            Config config = Initializer.CONFIG;
            config.windowMode = WindowMode.WINDOWED.mode;
        }
    }

    /**
     * @author
     */
    @Overwrite
    private void setMode() {
        Config config = Initializer.CONFIG;
        VideoModeManager.checkConfigVideoMode(config);

        if (this.fullscreen) {
            config.windowMode = WindowMode.EXCLUSIVE_FULLSCREEN.mode;
        }

        if (this.fullscreen) {
            {
                VideoModeManager.selectBestMonitor((Window) (Object) this);
                long monitor = VideoModeManager.selectedMonitor;
                VideoModeSet.VideoMode videoMode = config.videoMode;

                boolean supported;
                VideoModeSet set = VideoModeManager.getVideoModeSet(videoMode);

                if (set != null) {
                    supported = set.hasRefreshRate(videoMode.refreshRate);
                }
                else {
                    supported = false;
                }

                if (!supported) {
                    LOGGER.error("Resolution not supported, using first available as fallback");
                    videoMode = VideoModeManager.getFirstAvailable().getVideoMode();
                }

                if (!this.wasOnFullscreen) {
                    this.windowedX = this.x;
                    this.windowedY = this.y;
                    this.windowedWidth = this.width;
                    this.windowedHeight = this.height;
                }

                this.x = 0;
                this.y = 0;
                this.width = videoMode.width;
                this.height = videoMode.height;
                GLFW.glfwSetWindowMonitor(this.handle, monitor, this.x, this.y, this.width, this.height, videoMode.refreshRate);

                this.wasOnFullscreen = true;
            }
        }
        else if (config.windowMode == WindowMode.WINDOWED_FULLSCREEN.mode) {
            VideoModeManager.selectBestMonitor((Window) (Object) this);
            VideoModeSet.VideoMode videoMode = VideoModeManager.getOsVideoMode();

            if (!this.wasOnFullscreen) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
            }

            int width = videoMode.width;
            int height = videoMode.height;

            GLFW.glfwSetWindowAttrib(this.handle, GLFW_DECORATED, GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(this.handle, 0L, 0, 0, width, height, -1);

            this.width = width;
            this.height = height;
            this.wasOnFullscreen = true;
        } else {
            this.x = this.windowedX;
            this.y = this.windowedY;
            this.width = this.windowedWidth;
            this.height = this.windowedHeight;

            GLFW.glfwSetWindowMonitor(this.handle, 0L, this.x, this.y, this.width, this.height, -1);
            GLFW.glfwSetWindowAttrib(this.handle, GLFW_DECORATED, GLFW_TRUE);

            this.wasOnFullscreen = false;
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void onFramebufferResize(long window, int width, int height) {
        if (window == this.handle) {
            int prevWidth = this.framebufferWidth;
            int prevHeight = this.framebufferHeight;

            if (width <= 0 || height <= 0) {
                return;
            }

            if (width == prevWidth && height == prevHeight) {
                return;
            }

            this.framebufferWidth = width;
            this.framebufferHeight = height;

            Renderer.scheduleSwapChainUpdate();
        }
    }

    @Overwrite
    private void onResize(long window, int width, int height) {
        if (window == this.handle) {
            this.width = width;
            this.height = height;
        }
    }

}
