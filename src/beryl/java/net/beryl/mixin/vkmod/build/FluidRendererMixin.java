package net.beryl.mixin.vkmod.build;

import net.beryl.render.build.ExtTerrainBuilder;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import net.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's fluid tessellator already supplies normals; attach Beryl's fluid material ID
 * and the per-column water depth so the translucent shader can apply water absorption.
 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
    private static final int MAX_WATER_DEPTH_SCAN = 32;

    @Shadow
    private BuilderResources resources;

    private float waterDepthBeryl;

    @Inject(method = "renderLiquid", at = @At("HEAD"))
    private void computeWaterDepth(BlockState blockState, FluidState fluidState, BlockPos blockPos, CallbackInfo ci) {
        this.waterDepthBeryl = 0.0F;
        if (fluidState.isEmpty() || !fluidState.is(FluidTags.WATER)) return;

        BlockAndTintGetter region = this.resources.getRegion();
        if (region == null) return;

        // Only the exposed top surface carries depth. Submerged side faces are
        // hidden against their neighbour and would read the whole column.
        if (region.getFluidState(blockPos.above()).is(FluidTags.WATER)) return;

        int depth = 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(blockPos);
        for (int dy = 1; dy < MAX_WATER_DEPTH_SCAN; ++dy) {
            cursor.setY(blockPos.getY() - dy);
            if (region.getFluidState(cursor).is(FluidTags.WATER)) {
                ++depth;
            } else {
                break;
            }
        }
        this.waterDepthBeryl = depth;
    }

    @Redirect(method = "lambda$renderLiquid$0", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/render/vertex/TerrainBuilder;setBlockAttributes(Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void setFluidAttributes(TerrainBuilder builder, BlockState state) {
        builder.setBlockAttributes(state);
        if (builder instanceof ExtTerrainBuilder extended) {
            extended.setFluidBlockAttributes(state.getFluidState());
            extended.setWaterDepth(this.waterDepthBeryl);
        }
    }
}