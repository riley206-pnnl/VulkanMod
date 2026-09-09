package net.vulkanmod.mixin.render.feature;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessor {
    @Accessor("shadowFeatureRenderer")
    ShadowFeatureRenderer vulkanmod$getShadowFeatureRenderer();

    @Accessor("modelFeatureRenderer")
    ModelFeatureRenderer vulkanmod$getModelFeatureRenderer();

    @Accessor("modelPartFeatureRenderer")
    ModelPartFeatureRenderer vulkanmod$getModelPartFeatureRenderer();

    @Accessor("itemFeatureRenderer")
    ItemFeatureRenderer vulkanmod$getItemFeatureRenderer();

    @Accessor("bufferSource")
    MultiBufferSource.BufferSource vulkanmod$getBufferSource();

    @Accessor("outlineBufferSource")
    OutlineBufferSource vulkanmod$getOutlineBufferSource();

    @Accessor("crumblingBufferSource")
    MultiBufferSource.BufferSource vulkanmod$getCrumblingBufferSource();
}
