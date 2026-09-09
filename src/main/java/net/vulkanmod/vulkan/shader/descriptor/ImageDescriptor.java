package net.vulkanmod.vulkan.shader.descriptor;

import net.vulkanmod.shaders.PackDebug;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

public class ImageDescriptor implements Descriptor {

    private final int descriptorType;
    private final int binding;
    public final String qualifier;
    public final String name;
    public final int imageIdx;

    public boolean useSampler;
    public boolean isReadOnlyLayout;
    private int layout;
    private int mipLevel = -1;
    private VulkanImage customImage;

    public ImageDescriptor(int binding, String type, String name, int imageIdx, int descriptorType) {
        this.binding = binding;
        this.qualifier = type;
        this.name = name;
        this.imageIdx = imageIdx;

        if (this.imageIdx == -1) {
            throw new IllegalArgumentException();
        }

        this.descriptorType = descriptorType;

        boolean isStorageImage = isStorageImage();
        this.useSampler = !isStorageImage;
        setLayout(isStorageImage ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    @Override
    public int getBinding() {
        return binding;
    }

    @Override
    public int getType() {
        return descriptorType;
    }

    @Override
    public int getStages() {
        return VK_SHADER_STAGE_ALL_GRAPHICS | VK_SHADER_STAGE_COMPUTE_BIT;
    }

    public void setLayout(int layout) {
        this.layout = layout;
        this.isReadOnlyLayout = layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    public int getLayout() {
        return layout;
    }

    public void setMipLevel(int mipLevel) {
        this.mipLevel = mipLevel;
    }

    public int getMipLevel() {
        return mipLevel;
    }

    public VulkanImage getImage() {
        if (customImage == null && imageIdx == VTextureSelector.SIZE - 1) {
            PackDebug.resourceFallback(name, "unmapped shader resource");
        }
        return customImage != null ? customImage : VTextureSelector.getImage(this.imageIdx);
    }

    /** Bind an Iris custom texture for this program without changing its global sampler alias. */
    public void setCustomImage(VulkanImage image) {
        this.customImage = image;
    }

    public long getImageView(VulkanImage image) {
        long view;

        if (mipLevel == -1)
            view = image.getImageView();
        else
            view = image.getLevelImageView(mipLevel);

        return view;
    }

    public boolean isStorageImage() {
        return this.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    }

    /** Shadow samplers need a comparison sampler, while the same Iris shadow
     * image is also exposed as raw depth to blocker-search/composite passes. */
    public boolean isComparisonSampler() {
        return (qualifier != null && qualifier.toLowerCase().contains("shadow"))
                || ("ShadowMap".equalsIgnoreCase(name) || "ShadowMap1".equalsIgnoreCase(name));
    }

    /**
     * Merge declarations from both shader stages. Iris packs sometimes
     * declare the same shadow image once as sampler2D (raw depth) and once as
     * sampler2DShadow (comparison lookup), reusing one binding. Vulkan keeps
     * the comparison operation in the sampler object, so retaining the first
     * declaration can accidentally bind a non-comparison sampler to a shadow
     * lookup. Prefer the stricter resource contract when declarations collide.
     */
    public static ImageDescriptor prefer(ImageDescriptor existing,
                                         ImageDescriptor candidate) {
        return prefer(existing, candidate, false);
    }

    /** Merge with the one Complementary exception: composite1 deliberately
     * reads shadowtex0 as raw depth for its blocker search. */
    public static ImageDescriptor prefer(ImageDescriptor existing,
                                         ImageDescriptor candidate,
                                         boolean preferRawShadowDepth) {
        if (existing == null) return candidate;
        if (preferRawShadowDepth && "shadowtex0".equals(candidate.name)
                && "shadowtex0".equals(existing.name)) {
            if (!candidate.isComparisonSampler() && existing.isComparisonSampler()) return candidate;
            if (!existing.isComparisonSampler() && candidate.isComparisonSampler()) return existing;
        }
        if (candidate.isStorageImage() && !existing.isStorageImage()) return candidate;
        if (candidate.isComparisonSampler() && !existing.isComparisonSampler()) return candidate;
        return existing;
    }

    @Override
    public String toString() {
        return "ImageDescriptor{" +
               "descriptorType=" + descriptorType +
               ", binding=" + binding +
               ", qualifier='" + qualifier + '\'' +
               ", name='" + name + '\'' +
               ", imageIdx=" + imageIdx +
               ", useSampler=" + useSampler +
               ", isReadOnlyLayout=" + isReadOnlyLayout +
               ", layout=" + layout +
               ", mipLevel=" + mipLevel +
               '}';
    }

    public static class State {
        long imageView, sampler;

        public State(long imageView, long sampler) {
            set(imageView, sampler);
        }

        public void set(long imageView, long sampler) {
            this.imageView = imageView;
            this.sampler = sampler;
        }

        public boolean isCurrentState(long imageView, long sampler) {
            return this.imageView == imageView && this.sampler == sampler;
        }

    }
}
