package net.vulkanmod.shaders;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.VBO;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.util.List;

/** Renders vanilla weather columns through the shader-pack weather G-buffer. */
public final class PackWeatherRenderer {
    private static final Identifier RAIN = Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW = Identifier.withDefaultNamespace("textures/environment/snow.png");

    private PackWeatherRenderer() {}

    public static boolean render(Vec3 camera, WeatherRenderState state) {
        GraphicsPipeline pipeline = PipelineManager.getPackWeatherShader();
        if (pipeline == null) return false;
        if (state.rainColumns.isEmpty() && state.snowColumns.isEmpty()) return true;

        Renderer renderer = Renderer.getInstance();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PackFramebuffers.beginTerrainMRT(Renderer.getCommandBuffer(), stack,
                    PackTerrainPipeline.getDrawBuffers(pipeline));
        }

        VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
        VRenderSystem.calculateMVP();
        VRenderSystem.enableBlend();
        VRenderSystem.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        VRenderSystem.enableDepthTest();
        VRenderSystem.glDepthFun(GL11.GL_LEQUAL);
        VRenderSystem.enableCull();
        VRenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightmap());
        drawColumns(camera, state.rainColumns, 1.0f, RAIN, pipeline);
        drawColumns(camera, state.snowColumns, 0.8f, SNOW, pipeline);

        PackFramebuffers.endTerrainMRT(Renderer.getCommandBuffer());
        renderer.getMainPass().rebindMainTarget();
        return true;
    }

    private static void drawColumns(Vec3 camera,
                                    List<WeatherEffectRenderer.ColumnInstance> columns,
                                    float maxAlpha,
                                    Identifier textureId,
                                    GraphicsPipeline pipeline) {
        if (columns.isEmpty()) return;
        MeshData mesh = buildMesh(camera, columns, maxAlpha);
        if (mesh == null) return;

        var texture = Minecraft.getInstance().getTextureManager().getTexture(textureId).getTextureView();
        VRenderSystem.setShaderTexture(0, texture);
        VTextureSelector.bindShaderTextures(pipeline);
        PackTerrainPipeline.update(pipeline);
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VBO vbo = new VBO(true);
        vbo.upload(mesh);
        vbo.bind(pipeline);
        vbo.draw();
        vbo.close();
    }

    private static MeshData buildMesh(Vec3 camera,
                                      List<WeatherEffectRenderer.ColumnInstance> columns,
                                      float maxAlpha) {
        ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(
                columns.size() * DefaultVertexFormat.PARTICLE.getVertexSize() * 4);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            for (WeatherEffectRenderer.ColumnInstance column : columns) {
                float relativeX = (float) (column.x() + 0.5 - camera.x());
                float relativeZ = (float) (column.z() + 0.5 - camera.z());
                float distanceSq = (float) Mth.lengthSquared(relativeX, relativeZ);
                float radius = Math.max(1.0f, Minecraft.getInstance().options.weatherRadius().get());
                float alpha = Mth.lerp(Math.min(distanceSq / (radius * radius), 1.0f), maxAlpha, 0.5f);
                int color = ARGB.white(alpha);

                int gridX = column.x() - Mth.floor(camera.x()) + 16;
                int gridZ = column.z() - Mth.floor(camera.z()) + 16;
                float dx = gridX - 16.0f;
                float dz = gridZ - 16.0f;
                float length = Mth.length(dx, dz);
                float halfX = (length == 0.0f ? 0.0f : -dz / length) * 0.5f;
                float halfZ = (length == 0.0f ? 0.0f : dx / length) * 0.5f;
                float x0 = relativeX - halfX, x1 = relativeX + halfX;
                float z0 = relativeZ - halfZ, z1 = relativeZ + halfZ;
                float y0 = column.bottomY() - (float) camera.y();
                float y1 = column.topY() - (float) camera.y();
                float u0 = column.uOffset(), u1 = u0 + 1.0f;
                float v0 = column.bottomY() * 0.25f + column.vOffset();
                float v1 = column.topY() * 0.25f + column.vOffset();

                builder.addVertex(x0, y1, z0).setUv(u0, v0).setColor(color).setLight(column.lightCoords());
                builder.addVertex(x1, y1, z1).setUv(u1, v0).setColor(color).setLight(column.lightCoords());
                builder.addVertex(x1, y0, z1).setUv(u1, v1).setColor(color).setLight(column.lightCoords());
                builder.addVertex(x0, y0, z0).setUv(u0, v1).setColor(color).setLight(column.lightCoords());
            }
        // MeshData owns the backing ByteBufferBuilder after build.  Closing
        // bytes here invalidates the returned mesh before VBO.upload() can
        // copy it, which only becomes visible when the routed weather pass
        // has active rain/snow columns.
        return builder.buildOrThrow();
    }
}
