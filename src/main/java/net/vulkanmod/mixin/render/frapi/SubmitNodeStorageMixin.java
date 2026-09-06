package net.vulkanmod.mixin.render.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessRenderCommandQueue;
import net.vulkanmod.render.chunk.build.frapi.mesh.MeshImpl;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(SubmitNodeStorage.class)
abstract class SubmitNodeStorageMixin implements SubmitNodeCollector, AccessRenderCommandQueue {
    @Override
    public void submitItem(
            PoseStack matrices,
            ItemDisplayContext displayContext,
            int light,
            int overlay,
            int outlineColors,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType glintType,
            MeshImpl mesh
    ) {
        OrderedSubmitNodeCollector queue = order(0);

        if (queue instanceof AccessRenderCommandQueue access) {
            access.submitItem(matrices, displayContext, light, overlay, outlineColors, tintLayers, quads, glintType, mesh);
        } else {
            queue.submitItem(matrices, displayContext, light, overlay, outlineColors, tintLayers, quads, glintType);
        }
    }
}
