package net.vulkanmod.mixin.render.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessBatchingRenderCommandQueue;
import net.vulkanmod.render.chunk.build.frapi.render.ItemRenderContext;
import net.vulkanmod.render.chunk.build.frapi.render.MeshItemCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererM {

    @Unique private final PoseStack poseStack = new PoseStack();
    @Unique private final ItemRenderContext itemRenderContext = new ItemRenderContext();

    @Inject(method = "renderSolid", at = @At("RETURN"))
    private void onReturnRenderSolid(SubmitNodeCollection queue, MultiBufferSource.BufferSource vertexConsumers, OutlineBufferSource outlineVertexConsumers, CallbackInfo ci) {
        renderMeshes(queue, vertexConsumers, outlineVertexConsumers, false);
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"))
    private void onReturnRenderTranslucent(SubmitNodeCollection queue, MultiBufferSource.BufferSource vertexConsumers, OutlineBufferSource outlineVertexConsumers, CallbackInfo ci) {
        renderMeshes(queue, vertexConsumers, outlineVertexConsumers, true);
    }

    @Unique
    private void renderMeshes(SubmitNodeCollection queue, MultiBufferSource.BufferSource vertexConsumers, OutlineBufferSource outlineVertexConsumers, boolean translucent) {
        if (!(queue instanceof AccessBatchingRenderCommandQueue batchingQueue)) {
            return;
        }

        for (MeshItemCommand itemCommand : batchingQueue.getMeshItemCommands()) {
            boolean isTranslucent = hasTranslucency(itemCommand);
            if (isTranslucent != translucent) {
                continue;
            }

            poseStack.pushPose();
            poseStack.last().set(itemCommand.positionMatrix());

            RenderType defaultLayer = null;
            for (BakedQuad quad : itemCommand.quads()) {
                defaultLayer = quad.materialInfo().itemRenderType();
                break;
            }
            if (defaultLayer == null) {
                defaultLayer = Sheets.cutoutBlockSheet();
            }

            itemRenderContext.renderModel(itemCommand.displayContext(), poseStack, vertexConsumers, itemCommand.lightCoords(), itemCommand.overlayCoords(), itemCommand.tintLayers(), itemCommand.quads(), itemCommand.mesh(), defaultLayer, itemCommand.glintType(), false);

            if (itemCommand.outlineColor() != 0) {
                outlineVertexConsumers.setColor(itemCommand.outlineColor());
                itemRenderContext.renderModel(itemCommand.displayContext(), poseStack, outlineVertexConsumers, itemCommand.lightCoords(), itemCommand.overlayCoords(), itemCommand.tintLayers(), itemCommand.quads(), itemCommand.mesh(), defaultLayer, ItemStackRenderState.FoilType.NONE, true);
            }

            poseStack.popPose();
        }
    }

    @Unique
    private static boolean hasTranslucency(MeshItemCommand itemCommand) {
        for (BakedQuad quad : itemCommand.quads()) {
            if (quad.materialInfo().itemRenderType().hasBlending()) {
                return true;
            }
        }
        return false;
    }
}
