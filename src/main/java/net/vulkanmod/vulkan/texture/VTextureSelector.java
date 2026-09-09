package net.vulkanmod.vulkan.texture;

import net.vulkanmod.Initializer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.shaders.PackDebug;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public abstract class VTextureSelector {
    public static final int SIZE = 32;

    private static final VulkanImage[] boundTextures = new VulkanImage[SIZE];

    private static final int[] levels = new int[SIZE];

    private static final VulkanImage whiteTexture = VulkanImage.createWhiteTexture();
    private static final VulkanImage whiteDepthTexture = VulkanImage.createWhiteDepthTexture();

    private static int activeTexture = 0;

    public static void bindTexture(VulkanImage texture) {
        boundTextures[0] = texture;
    }

    public static void bindTexture(int i, VulkanImage texture) {
        if (i < 0 || i >= SIZE) {
            Initializer.LOGGER.error(String.format("On Texture binding: index %d out of range [0, %d]", i, SIZE - 1));
            return;
        }

        boundTextures[i] = texture;
        levels[i] = -1;
    }

    public static void bindTexture(int i, VulkanImage texture, int viewFormat) {
        if (i < 0 || i >= SIZE) {
            Initializer.LOGGER.error(String.format("On Texture binding: index %d out of range [0, %d]", i, SIZE - 1));
            return;
        }

        texture.setViewFormat(viewFormat);

        boundTextures[i] = texture;
        levels[i] = -1;
    }

    public static void bindImage(int i, VulkanImage texture, int level) {
        if (i < 0 || i >= SIZE) {
            Initializer.LOGGER.error(String.format("On Texture binding: index %d out of range [0, %d]", i, SIZE - 1));
            return;
        }

        boundTextures[i] = texture;
        levels[i] = level;
    }

    public static void uploadSubTexture(int mipLevel, int width, int height, int xOffset, int yOffset,
                                        int unpackSkipRows, int unpackSkipPixels, int unpackRowLength,
                                        ByteBuffer buffer) {
        uploadSubTexture(mipLevel, 0, width, height, xOffset, yOffset, unpackSkipRows, unpackSkipPixels, unpackRowLength,
                         MemoryUtil.memAddress(buffer));
    }

    public static void uploadSubTexture(int mipLevel, int arrayLayer, int width, int height, int xOffset, int yOffset,
                                        int unpackSkipRows, int unpackSkipPixels, int unpackRowLength,
                                        long bufferPtr) {
        VulkanImage texture = boundTextures[activeTexture];

        if (texture == null)
            throw new NullPointerException("Texture is null at index: " + activeTexture);

        // Images need to be transitioned before main cmd buffer execution
        SpriteUpdateUtil.addTransitionedLayout(texture);

        texture.uploadSubTextureAsync(mipLevel, arrayLayer, width, height, xOffset, yOffset, unpackSkipRows, unpackSkipPixels,
                                      unpackRowLength, bufferPtr);
    }

    public static int getTextureIdx(String name) {
        return switch (name) {
            case "Sampler0", "DiffuseSampler", "InSampler", "CloudFaces", "Sprite", "CurrentSprite", "tex" -> 0;
            case "Sampler1", "BlurSampler", "NextSprite" -> 1;
            case "Sampler2", "LightTexture", "lightmap" -> 2;
            case "Sampler3", "ShadowMap" -> 3;
            case "Sampler4", "ShadowMap1", "Framebuffer" -> 4;
            case "Sampler5", "ShadowColor", "ShadowMapColor", "Depthbuffer" -> 5;
            case "Sampler6" -> 6;
            case "Sampler7" -> 7;
            case "colortex0", "gcolor" -> 8;
            case "colortex1", "gdepth" -> 9;
            case "colortex2", "gnormal" -> 10;
            case "colortex3", "composite" -> 11;
            case "colortex4", "gaux1" -> 12;
            case "colortex5", "gaux2" -> 13;
            case "colortex6", "gaux3" -> 14;
            case "colortex7", "gaux4" -> 15;
            case "depthtex0", "depthtex2" -> 16;
            case "depthtex1" -> 17;
            case "shadowtex0" -> 18;
            case "shadowtex1" -> 19;
            case "shadowcolor0" -> 20;
            case "shadowcolor1" -> 21;
            case "noisetex", "noise" -> 22;
            case "colortex8" -> 23;
            case "colortex9" -> 24;
            case "colortex10" -> 25;
            case "colortex11" -> 26;
            case "colortex12" -> 27;
            case "colortex13" -> 28;
            case "colortex14" -> 29;
            case "colortex15" -> 30;
            default -> SIZE - 1;
        };
    }

    public static void bindShaderTextures(Pipeline pipeline) {
        var imageDescriptors = pipeline.getImageDescriptors();

        for (ImageDescriptor state : imageDescriptors) {
            var textureView = VRenderSystem.getShaderTexture(state.imageIdx);

            if (textureView == null) {
                // If a texture is already bound (e.g. by Beryl or shader-pack framebuffer manager),
                // do not clobber it with white placeholder. Only fall back when the slot is actually null/unbound.
                if (boundTextures[state.imageIdx] == null) {
                    PackDebug.resourceFallback(state.name,
                            state.imageIdx == SIZE - 1 ? "unmapped sampler binding" : "unbound texture slot " + state.imageIdx);
                    boolean isDepth = (state.imageIdx == 3 || (state.imageIdx >= 16 && state.imageIdx <= 19)
                            || (state.name != null && (state.name.toLowerCase().contains("depth") || state.name.toLowerCase().contains("shadow"))));
                    VTextureSelector.bindTexture(state.imageIdx,
                            isDepth ? whiteDepthTexture : whiteTexture);
                }
                continue;
            }

            VkGpuTexture gpuTexture = (VkGpuTexture) textureView.texture();

            final int shaderTexture = gpuTexture.glId();
            VkGlTexture texture = VkGlTexture.getTexture(shaderTexture);

            if (texture != null && texture.getVulkanImage() != null) {
                VTextureSelector.bindTexture(state.imageIdx, texture.getVulkanImage());
            } else {
                PackDebug.resourceFallback(state.name, "invalid Vulkan texture handle");
                VTextureSelector.bindTexture(state.imageIdx, whiteTexture);
            }
            // TODO
//            else {
//                 texture = GlTexture.getTexture(MissingTextureAtlasSprite.getTexture().getId());
//                 VTextureSelector.bindTexture(state.imageIdx, texture.getVulkanImage());
//            }
        }
    }

    public static VulkanImage getImage(int i) {
        if (i < 0 || i >= SIZE) return whiteTexture;
        VulkanImage img = boundTextures[i];
        if (img != null) return img;
        if ((i == 3 || (i >= 16 && i <= 19)) && whiteDepthTexture != null) {
            return whiteDepthTexture;
        }
        return whiteTexture;
    }

    public static void setLightTexture(VulkanImage texture) {
        boundTextures[2] = texture;
    }

    public static void setOverlayTexture(VulkanImage texture) {
        boundTextures[1] = texture;
    }

    public static void setActiveTexture(int activeTexture) {
        if (activeTexture < 0 || activeTexture >= SIZE) {
            Initializer.LOGGER.error(
                    String.format("On Texture binding: index %d out of range [0, %d]", activeTexture, SIZE - 1));
        }

        VTextureSelector.activeTexture = activeTexture;
    }

    public static VulkanImage getBoundTexture() {
        return boundTextures[activeTexture];
    }

    public static VulkanImage getBoundTexture(int i) {
        return boundTextures[i];
    }

    public static VulkanImage[] getBoundTextures() {
        return boundTextures;
    }

    public static VulkanImage getWhiteTexture() {
        return whiteTexture;
    }
}
