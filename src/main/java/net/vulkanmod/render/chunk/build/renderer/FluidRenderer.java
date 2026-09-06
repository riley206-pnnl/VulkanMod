package net.vulkanmod.render.chunk.build.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;

public class FluidRenderer {
    private BuilderResources resources;
    private net.minecraft.client.renderer.block.FluidRenderer vanillaFluidRenderer;

    public FluidRenderer(LightPipeline flatLightPipeline, LightPipeline smoothLightPipeline) {
    }

    public void setResources(BuilderResources resources) {
        this.resources = resources;
    }

    private net.minecraft.client.renderer.block.FluidRenderer getFluidRenderer() {
        if (this.vanillaFluidRenderer == null) {
            this.vanillaFluidRenderer = new net.minecraft.client.renderer.block.FluidRenderer(
                    Minecraft.getInstance().getModelManager().getFluidStateModelSet()
            );
        }
        return this.vanillaFluidRenderer;
    }

    public void renderLiquid(BlockState blockState, FluidState fluidState, BlockPos blockPos) {
        BlockAndTintGetter region = this.resources.getRegion();
        getFluidRenderer().tesselate(region, blockPos, layer -> {
            TerrainRenderType renderType = TerrainRenderType.get(layer);
            renderType = TerrainRenderType.getRemapped(renderType);
            return this.resources.builderPack.builder(renderType).getBufferBuilder(QuadFacing.UNDEFINED.ordinal());
        }, blockState, fluidState);
    }
}
