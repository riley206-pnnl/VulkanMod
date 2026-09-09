package net.vulkanmod.shaders;

import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanmod.Initializer;
import net.vulkanmod.mixin.texture.image.NativeImageAccessor;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

public final class PackFramebuffers {
    private static final int COLOR_TEXTURE_COUNT = 16;
    private static int width = 0;
    private static int height = 0;

    public static final int COLOR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    public static final int SHADOW_COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    public static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
    public static final int DEFAULT_SHADOW_RESOLUTION = 1024;
    private static int shadowResolution = DEFAULT_SHADOW_RESOLUTION;

    private static final VulkanImage[][] colortex = new VulkanImage[COLOR_TEXTURE_COUNT][2];
    private static final int[] readIdx = new int[COLOR_TEXTURE_COUNT];
    /** First-use state for the camera depth target in the current frame. */
    private static boolean depthtex0Initialized;
    private static VulkanImage depthtex0;
    private static VulkanImage depthtex1;

    private static VulkanImage shadowtex0;
    private static VulkanImage shadowtex1;
    private static VulkanImage shadowcolor0;
    private static VulkanImage shadowcolor1;

    private static VulkanImage noisetex;
    private static final Map<String, VulkanImage> customTextures = new LinkedHashMap<>();

    public static synchronized void init(int w, int h, ShaderPack pack) {
        init(w, h, pack, null);
    }

    public static synchronized void init(int w, int h, ShaderPack pack, ShaderPackConfig config) {
        int requestedShadowResolution = config == null
                ? ShaderFeatureProfile.fromSystemProperty().shadowResolution()
                : config.shadowResolution();
        init(w, h, pack, config, requestedShadowResolution);
    }

    /**
     * Allocate pack targets using the resolved dimension-specific shadow size.
     * The dimension override must be applied before the early-out check; a
     * world change can keep the swapchain size while changing shadow quality.
     */
    public static synchronized void init(int w, int h, ShaderPack pack, ShaderPackConfig config,
                                         int requestedShadowResolution) {
        if (width == w && height == h && shadowResolution == requestedShadowResolution
                && colortex[0][0] != null) return;
        cleanUp();

        width = w;
        height = h;
        depthtex0Initialized = false;
        // The resolved pack profile includes shaders.properties overrides.
        // Falling back to the host tier is only valid before a pack config is
        // available during renderer bootstrap.
        shadowResolution = requestedShadowResolution;

        int colorUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        int depthUsage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

        for (int i = 0; i < COLOR_TEXTURE_COUNT; i++) {
            readIdx[i] = 0;
            for (int p = 0; p < 2; p++) {
                colortex[i][p] = VulkanImage.builder(width, height)
                        .setName("colortex" + i + "_" + (p == 0 ? "main" : "alt"))
                        .setFormat(COLOR_FORMAT)
                        .setUsage(colorUsage)
                        .setLinearFiltering(true)
                        .setClamp(true)
                        .createVulkanImage();
            }
            VTextureSelector.bindTexture(textureBinding(i), colortex[i][0]);
        }

        depthtex0 = VulkanImage.builder(width, height)
                .setName("depthtex0")
                .setFormat(DEPTH_FORMAT)
                .setUsage(depthUsage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        VTextureSelector.bindTexture(16, depthtex0);

        depthtex1 = VulkanImage.builder(width, height)
                .setName("depthtex1")
                .setFormat(DEPTH_FORMAT)
                .setUsage(depthUsage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        VTextureSelector.bindTexture(17, depthtex1);

        int shadowRes = shadowResolution;
        shadowtex0 = VulkanImage.builder(shadowRes, shadowRes)
                .setName("shadowtex0")
                .setFormat(DEPTH_FORMAT)
                .setUsage(depthUsage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        // shadowtex0 is the raw depth texture used by composite1 for manual
        // blocker searches.  It must remain a normal sampler; shadowtex1 is
        // the filtered comparison sampler used by the lighting passes.
        VTextureSelector.bindTexture(18, shadowtex0);

        shadowtex1 = VulkanImage.builder(shadowRes, shadowRes)
                .setName("shadowtex1")
                .setFormat(DEPTH_FORMAT)
                .setUsage(depthUsage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
        VTextureSelector.bindTexture(19, shadowtex1);

        shadowcolor0 = VulkanImage.builder(shadowRes, shadowRes)
                .setName("shadowcolor0")
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setUsage(colorUsage)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        VTextureSelector.bindTexture(20, shadowcolor0);

        shadowcolor1 = VulkanImage.builder(shadowRes, shadowRes)
                .setName("shadowcolor1")
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setUsage(colorUsage)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        VTextureSelector.bindTexture(21, shadowcolor1);

        if (Boolean.getBoolean("vulkanmod.disablePackTextureUploads")) {
            Initializer.LOGGER.warn("Shader-pack texture uploads disabled by diagnostic property");
        } else {
            initNoiseTexture(pack);
            initCustomTextures(pack, config);
        }
    }

    private static void initCustomTextures(ShaderPack pack, ShaderPackConfig config) {
        if (pack == null || config == null) return;
        for (String program : new String[]{
                "gbuffers", "gbuffers_terrain", "gbuffers_water", "deferred", "composite",
                "composite1", "composite2", "composite3", "composite4", "composite5",
                "composite6", "composite7", "final"}) {
            for (var entry : config.properties().customTextures(program).entrySet()) {
                String key = program + "\u0000" + entry.getKey();
                if (customTextures.containsKey(key)) continue;
                VulkanImage image = loadCustomTexture(pack, entry.getValue(),
                        "custom_" + program + "_" + entry.getKey());
                if (image != null) customTextures.put(key, image);
            }
        }
    }

    private static VulkanImage loadCustomTexture(ShaderPack pack, String path, String name) {
        if (path == null || path.isBlank()) {
            Initializer.LOGGER.warn("Shader custom texture {} has an empty path", name);
            return null;
        }
        if (path.contains(":")) {
            Initializer.LOGGER.warn("Shader custom texture {} uses unsupported namespaced path {}", name, path);
            return null;
        }
        try {
            InputStream in = pack.openStream("shaders/" + path);
            if (in == null) in = pack.openStream(path);
            if (in == null) {
                Initializer.LOGGER.warn("Shader custom texture {} was not found at {}", name, path);
                return null;
            }
            try (InputStream stream = in; NativeImage image = NativeImage.read(stream)) {
                VulkanImage texture = VulkanImage.builder(image.getWidth(), image.getHeight())
                        .setName(name)
                        .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                        .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                        .setLinearFiltering(true)
                        .setClamp(false)
                        .createVulkanImage();
                long pixels = ((NativeImageAccessor) (Object) image).getPixels();
                texture.uploadSubTextureAsync(0, 0, image.getWidth(), image.getHeight(),
                        0, 0, 0, 0, image.getWidth(), pixels);
                // Pack-owned images do not pass through VTextureSelector's
                // normal upload path. Register the image so the upload
                // command transitions it from TRANSFER_DST_OPTIMAL to
                // SHADER_READ_ONLY_OPTIMAL before a shader samples it.
                SpriteUpdateUtil.addTransitionedLayout(texture);
                Initializer.LOGGER.info("Loaded shader custom texture {} -> {}", path, name);
                return texture;
            }
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to load shader custom texture {}: {}", path, t.toString());
            return null;
        }
    }

    public static VulkanImage getCustomTexture(String program, String sampler) {
        VulkanImage image = customTextures.get(program + "\u0000" + sampler);
        if (image != null) return image;
        String family = program;
        if (program.startsWith("gbuffers_")) family = "gbuffers";
        else if (program.startsWith("deferred")) family = "deferred";
        else if (program.startsWith("composite")) family = "composite";
        return customTextures.get(family + "\u0000" + sampler);
    }

    public static void bindCustomTexture(String program, net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor descriptor) {
        VulkanImage image = getCustomTexture(program, descriptor.name);
        if (image != null) descriptor.setCustomImage(image);
    }

    private static void initNoiseTexture(ShaderPack pack) {
        if (noisetex != null) return;
        try {
            InputStream in = pack != null ? pack.openStream("shaders/lib/textures/noise.png") : null;
            if (in == null && pack != null) in = pack.openStream("shaders/textures/noise.png");
            if (in != null) {
                try (InputStream stream = in; NativeImage img = NativeImage.read(stream)) {
                    int nw = img.getWidth();
                    int nh = img.getHeight();
                    noisetex = VulkanImage.builder(nw, nh)
                            .setName("noisetex")
                            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                            .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                            .setLinearFiltering(false)
                            .setClamp(false)
                            .createVulkanImage();
                    long ptr = ((NativeImageAccessor) (Object) img).getPixels();
                    noisetex.uploadSubTextureAsync(0, 0, nw, nh, 0, 0, 0, 0, nw, ptr);
                    VTextureSelector.bindTexture(22, noisetex);
                    return;
                }
            }
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to load noise texture from pack: {}", t.getMessage());
        }

        int nw = 64, nh = 64;
        noisetex = VulkanImage.builder(nw, nh)
                .setName("noisetex_fallback")
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(false)
                .setClamp(false)
                .createVulkanImage();
        ByteBuffer buf = memAlloc(nw * nh * 4);
        Random rnd = new Random(12345);
        byte[] bytes = new byte[nw * nh * 4];
        rnd.nextBytes(bytes);
        buf.put(bytes).flip();
        noisetex.uploadSubTextureAsync(0, 0, nw, nh, 0, 0, 0, 0, nw, buf);
        memFree(buf);
        VTextureSelector.bindTexture(22, noisetex);
    }

    public static VulkanImage getColortex(int i) {
        return (i >= 0 && i < colortex.length) ? colortex[i][readIdx[i]] : null;
    }

    public static VulkanImage getColortexWrite(int i) {
        return (i >= 0 && i < colortex.length) ? colortex[i][1 - readIdx[i]] : null;
    }

    public static void flip(int i) {
        if (i < 0 || i >= colortex.length) return;
        readIdx[i] = 1 - readIdx[i];
        VTextureSelector.bindTexture(textureBinding(i), colortex[i][readIdx[i]]);
    }

    public static VulkanImage getDepthtex0() {
        return depthtex0;
    }

    public static VulkanImage getDepthtex1() {
        return depthtex1;
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    public static int getShadowResolution() {
        return shadowResolution;
    }

    public static void copyDepthToDepthtex1(VkCommandBuffer cmd) {
        if (depthtex0 == null || depthtex1 == null) return;
        try (MemoryStack stack = stackPush()) {
            depthtex0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            depthtex1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
            copyRegion.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            copyRegion.srcSubresource().layerCount(1);
            copyRegion.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            copyRegion.dstSubresource().layerCount(1);
            copyRegion.extent().set(width, height, 1);

            vkCmdCopyImage(cmd, depthtex0.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    depthtex1.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copyRegion);

            depthtex0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depthtex1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    public static void resetPingPong() {
        for (int i = 0; i < colortex.length; i++) {
            readIdx[i] = 0;
            if (colortex[i][0] != null) {
                VTextureSelector.bindTexture(textureBinding(i), colortex[i][0]);
            }
        }
    }

    /**
     * Iris initializes every declared color target to zero at the start of a
     * frame. Vulkan image allocation does not provide that guarantee, and
     * composite passes are allowed to read targets that no earlier pass has
     * written yet (Complementary intentionally treats those as zero). Clear
     * both ping-pong images so the first read and every subsequent flip have
     * deterministic contents.
     */
    public static void clearColorTargets(VkCommandBuffer cmd, MemoryStack stack) {
        // Sky has no depth attachment. The first subsequent G-buffer scope
        // must clear depthtex0, while later scopes in this frame load it.
        depthtex0Initialized = false;
        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        clear.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        for (int i = 0; i < COLOR_TEXTURE_COUNT; i++) {
            for (int p = 0; p < 2; p++) {
                VulkanImage image = colortex[i][p];
                if (image == null) continue;
                image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                vkCmdClearColorImage(cmd, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        clear, range);
                image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
        rebindReadTextures();
    }

    /** Reassert the current ping-pong read images after a vanilla/terrain pipeline rebinding. */
    public static void rebindReadTextures() {
        for (int i = 0; i < colortex.length; i++) {
            VulkanImage image = colortex[i][readIdx[i]];
            if (image != null) {
                VTextureSelector.bindTexture(textureBinding(i), image);
            }
        }
    }

    /** colortex0..7 occupy bindings 8..15; colortex8..15 occupy 23..30. */
    private static int textureBinding(int index) {
        return index < 8 ? 8 + index : 15 + index;
    }

    public static VulkanImage getShadowtex0() { return shadowtex0; }
    public static VulkanImage getShadowtex1() { return shadowtex1; }
    public static VulkanImage getShadowcolor0() { return shadowcolor0; }
    public static VulkanImage getShadowcolor1() { return shadowcolor1; }
    public static VulkanImage getNoisetex() { return noisetex; }

    /** Begin the pack shadow map render. The shadow pass owns its depth and
     * optional color attachments; it never aliases the camera G-buffer. */
    public static void beginShadow(VkCommandBuffer cmd, MemoryStack stack, int[] drawBuffers) {
        beginShadow(cmd, stack, drawBuffers, true);
    }

    public static void beginShadow(VkCommandBuffer cmd, MemoryStack stack, int[] drawBuffers, boolean clear) {
        final int shadowSize = shadowResolution;
        shadowtex0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        int count = drawBuffers == null ? 0 : drawBuffers.length;
        VkRenderingAttachmentInfo.Buffer colors = count == 0
                ? null : VkRenderingAttachmentInfo.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            VulkanImage image = switch (drawBuffers[i]) {
                case 0 -> shadowcolor0;
                case 1 -> shadowcolor1;
                default -> throw new IllegalArgumentException("Shadow DRAWBUFFERS target " + drawBuffers[i]);
            };
            image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkRenderingAttachmentInfo attachment = colors.get(i);
            attachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            attachment.imageView(image.getImageView());
            attachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachment.loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD);
            attachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            attachment.clearValue().color().float32(0, 0.0f).float32(1, 0.0f)
                    .float32(2, 0.0f).float32(3, 0.0f);
        }

        VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack);
        depth.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
        depth.imageView(shadowtex0.getImageView());
        depth.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        depth.loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD);
        depth.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        depth.clearValue().depthStencil().depth(1.0f).stencil(0);

        VkRect2D area = VkRect2D.malloc(stack);
        area.offset().set(0, 0);
        area.extent().set(shadowSize, shadowSize);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack);
        info.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        info.renderArea(area).layerCount(1).pColorAttachments(colors).pDepthAttachment(depth);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);

        Renderer.setViewport(0, 0, shadowSize, shadowSize, stack);
        VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(shadowSize, shadowSize);
        vkCmdSetScissor(cmd, 0, scissor);
    }

    public static void endShadow(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        Renderer.getInstance().clearBoundRenderPassState();
    }

    /** Make the completed light-space attachments readable before the camera
     * G-buffer starts sampling shadowtex/shadowcolor. */
    public static void transitionShadowsToRead(VkCommandBuffer cmd, MemoryStack stack) {
        if (shadowtex0 != null) shadowtex0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (shadowtex1 != null) shadowtex1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (shadowcolor0 != null) shadowcolor0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (shadowcolor1 != null) shadowcolor1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * Iris exposes two depth views of the shadow pass.  Complementary uses
     * shadowtex0 for filtered caster depth and shadowtex1 for the opaque-depth
     * lookup used by its lighting code.  We do not yet have a separate
     * translucent caster pass, but leaving shadowtex1 undefined makes every
     * lookup implementation-dependent.  Copy the populated depth map as a
     * deterministic baseline until the split caster policy is implemented.
     */
    public static void copyShadowDepthToSecondary(VkCommandBuffer cmd, MemoryStack stack) {
        if (shadowtex0 == null || shadowtex1 == null) return;
        shadowtex0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        shadowtex1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
        region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
        region.extent().set(shadowResolution, shadowResolution, 1);
        vkCmdCopyImage(cmd, shadowtex0.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                shadowtex1.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    public static void beginTerrainMRT(VkCommandBuffer cmd, MemoryStack stack, int[] drawBuffers) {
        int[] targets = (drawBuffers != null && drawBuffers.length > 0) ? drawBuffers : new int[]{0, 6};
        VulkanImage depth = getDepthtex0();
        depth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkRenderingAttachmentInfo.Buffer colorAttachments =
                VkRenderingAttachmentInfo.calloc(targets.length, stack);

        for (int i = 0; i < targets.length; i++) {
            int bufIdx = targets[i];
            VulkanImage c = getColortex(bufIdx);
            c.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            var att = colorAttachments.get(i);
            att.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            att.imageView(c.getImageView());
            att.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            att.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            att.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        }

        VkRenderingAttachmentInfo depthAttachment =
                VkRenderingAttachmentInfo.calloc(stack);
        depthAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
        depthAttachment.imageView(depth.getImageView());
        depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        boolean clearDepth = !depthtex0Initialized;
        depthAttachment.loadOp(clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD);
        depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        if (clearDepth) {
            depthAttachment.clearValue().depthStencil().depth(1.0f).stencil(0);
            depthtex0Initialized = true;
        }

        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(width, height);

        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
        renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        renderingInfo.renderArea(renderArea);
        renderingInfo.layerCount(1);
        renderingInfo.pColorAttachments(colorAttachments);
        renderingInfo.pDepthAttachment(depthAttachment);

        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        Renderer.setViewport(0, 0, width, height, stack);
        VkRect2D.Buffer pScissor = VkRect2D.malloc(1, stack);
        pScissor.offset().set(0, 0);
        pScissor.extent().set(width, height);
        vkCmdSetScissor(cmd, 0, pScissor);
    }

    public static void beginSky(VkCommandBuffer cmd, MemoryStack stack) {
        VulkanImage color = getColortex(0);
        color.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkRenderingAttachmentInfo attachment = VkRenderingAttachmentInfo.calloc(stack);
        attachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
        attachment.imageView(color.getImageView());
        attachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        attachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
        attachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        attachment.clearValue().color().float32(0, 0.0f).float32(1, 0.0f)
                .float32(2, 0.0f).float32(3, 1.0f);
        VkRect2D area = VkRect2D.malloc(stack);
        area.offset().set(0, 0);
        area.extent().set(width, height);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack);
        info.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(area).layerCount(1).pColorAttachments(
                        VkRenderingAttachmentInfo.calloc(1, stack).put(0, attachment));
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
        Renderer.setViewport(0, 0, width, height, stack);
        VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(width, height);
        vkCmdSetScissor(cmd, 0, scissor);
    }

    public static void endSky(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        Renderer.getInstance().clearBoundRenderPassState();
    }

    public static void endTerrainMRT(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        Renderer.getInstance().clearBoundRenderPassState();
    }

    public static synchronized void cleanUp() {
        for (int i = 0; i < colortex.length; i++) {
            for (int p = 0; p < 2; p++) {
                if (colortex[i][p] != null) {
                    colortex[i][p].free();
                    colortex[i][p] = null;
                }
            }
            readIdx[i] = 0;
        }
        if (depthtex0 != null) {
            depthtex0.free();
            depthtex0 = null;
        }
        if (depthtex1 != null) {
            depthtex1.free();
            depthtex1 = null;
        }
        if (shadowtex0 != null) {
            shadowtex0.free();
            shadowtex0 = null;
        }
        if (shadowtex1 != null) {
            shadowtex1.free();
            shadowtex1 = null;
        }
        if (shadowcolor0 != null) {
            shadowcolor0.free();
            shadowcolor0 = null;
        }
        if (shadowcolor1 != null) {
            shadowcolor1.free();
            shadowcolor1 = null;
        }
        if (noisetex != null) {
            noisetex.free();
            noisetex = null;
        }
        for (VulkanImage image : customTextures.values()) image.free();
        customTextures.clear();
        width = 0;
        height = 0;
        depthtex0Initialized = false;
    }

    private PackFramebuffers() {}
}
