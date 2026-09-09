package net.beryl.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.beryl.render.RenderingPipeline;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererM {
    @WrapMethod(method = "renderItem")
    private void beryl$handMaterial(MultiBufferSource.BufferSource buffers, OutlineBufferSource outlines,
                                    SubmitNodeStorage.ItemSubmit submit, Operation<Void> original) {
        if (!RenderingPipeline.isUsingShaderPipeline() || !submit.displayContext().firstPerson()) {
            original.call(buffers, outlines, submit);
            return;
        }
        // Submission is deferred: isolate this item's batch from the arm and the other hand.
        buffers.endBatch();
        int material = 0;
        if (!submit.quads().isEmpty()) {
            var sprite = submit.quads().getFirst().materialInfo().sprite().contents().name();
            if (sprite.getNamespace().equals("minecraft")) {
                String path = sprite.getPath();
                if (path.startsWith("item/iron_") || path.equals("block/iron_block")) material = 3;
                else if (path.startsWith("item/golden_") || path.equals("block/gold_block")) material = 4;
                else if (path.startsWith("item/copper_") || path.equals("block/copper_block")) material = 5;
                else if (path.startsWith("item/diamond_") || path.equals("block/diamond_block")) material = 21;
                else if (path.startsWith("item/netherite_") || path.equals("block/netherite_block")) material = 22;
            }
        }
        RenderingPipeline.handMaterial = material;
        try {
            original.call(buffers, outlines, submit);
            buffers.endBatch();
        } finally {
            RenderingPipeline.handMaterial = 0;
        }
    }
}
