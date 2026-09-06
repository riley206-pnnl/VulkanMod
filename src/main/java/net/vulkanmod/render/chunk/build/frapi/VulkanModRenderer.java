package net.vulkanmod.render.chunk.build.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessLayerRenderState;
import net.vulkanmod.render.chunk.build.frapi.helper.fabric.interfaces.QuadEmitter;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableMeshImpl;
import net.vulkanmod.render.chunk.build.frapi.render.BlockRenderContext;
import net.vulkanmod.render.chunk.build.frapi.render.SimpleBlockRenderContext;

/**
 * Fabric renderer implementation.
 */
public class VulkanModRenderer {
	public static final VulkanModRenderer INSTANCE = new VulkanModRenderer();

	private VulkanModRenderer() {}

	public MutableMeshImpl mutableMesh() {
		return new MutableMeshImpl();
	}

	public void render(ModelBlockRenderer modelBlockRenderer, BlockAndTintGetter blockAndTintGetter,
					   BlockStateModel blockStateModel, BlockState blockState, BlockPos blockPos, PoseStack poseStack,
					   VertexConsumer blockVertexConsumerProvider, boolean cull, long seed, int overlay) {
		BlockRenderContext.POOL.get().render(blockAndTintGetter, blockStateModel, blockState, blockPos, poseStack, blockVertexConsumerProvider, cull, seed, overlay);
	}

	public void render(PoseStack.Pose pose, VertexConsumer blockVertexConsumerProvider, BlockStateModel blockStateModel,
					   float v, float v1, float v2, int i, int i1, BlockAndTintGetter blockAndTintGetter,
					   BlockPos blockPos, BlockState blockState) {
		SimpleBlockRenderContext.POOL.get().bufferModel(pose, blockVertexConsumerProvider, blockStateModel, v, v1, v2, i, i1, blockAndTintGetter, blockPos, blockState);
	}

	public QuadEmitter getLayerRenderStateEmitter(ItemStackRenderState.LayerRenderState layer) {
		return ((AccessLayerRenderState) layer).getMutableMesh().emitter();
	}
}
