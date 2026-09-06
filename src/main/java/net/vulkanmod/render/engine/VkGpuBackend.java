package net.vulkanmod.render.engine;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.glfw.GLFW;

public class VkGpuBackend implements GpuBackend {
    @Override
    public String getName() {
        return "Vulkan";
    }

    @Override
    public void setWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
        if (error != null) {
            throw new BackendCreationException(error.description());
        }
    }

    @Override
    public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions) {
        try {
            Vulkan.initVulkanIfNeeded(window);
        } catch (RuntimeException | Error failure) {
            net.vulkanmod.Initializer.LOGGER.error("Failed to initialize Vulkan", failure);
            throw failure;
        }
        VkGpuDevice vkDevice = new VkGpuDevice(window, 0, false, defaultShaderSource, debugOptions.useLabels());
        return new GpuDevice(vkDevice);
    }
}
