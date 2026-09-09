package net.vulkanmod.render.chunk.buffer;

import net.minecraft.util.Mth;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.task.CompiledSection;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.shaders.PackDebug;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import static org.lwjgl.vulkan.VK10.*;

public class DrawBuffers {
    public static final int INDEX_SIZE = Short.BYTES;
    public static final int UNDEFINED_FACING_IDX = QuadFacing.UNDEFINED.ordinal();
    public static final float POS_OFFSET = CustomVertexFormat.getPositionOffset();

    private static final int CMD_STRIDE = 32;
    private static long lastShadowDrawDiagnostic = -1L;

    private static final long cmdBufferPtr = MemoryUtil.nmemAlignedAlloc(CMD_STRIDE, (long) ChunkAreaManager.AREA_SIZE * QuadFacing.COUNT * CMD_STRIDE);

    private final int index;
    public final int vertexSize = PipelineManager.getTerrainVertexFormat().getVertexSize();
    private final float positionOffset = CustomVertexFormat.getPositionOffset();
    private final Vector3i origin;
    private final int minHeight;

    private boolean allocated = false;
    AreaBuffer indexBuffer;
    private final EnumMap<TerrainRenderType, AreaBuffer> vertexBuffers = new EnumMap<>(TerrainRenderType.class);

    private final UniformBuffer sectionDataBuffer = new UniformBuffer(ChunkAreaManager.AREA_SIZE * 2 * 4, MemoryTypes.HOST_MEM);

    final long drawParamsPtr;
    final int[] sectionIndices = new int[512];
    final int[] masks = new int[512];

    final long[] buildTimes = new long[512];
    long latestBuildTime = 0;
    long lastFadeUpdate = -1;

    // Need ugly minHeight parameter to fix custom world heights (exceeding 384 Blocks in total)
    public DrawBuffers(int index, Vector3i origin, int minHeight) {
        this.index = index;
        this.origin = origin;
        this.minHeight = minHeight;

        this.drawParamsPtr = DrawParametersBuffer.allocateBuffer();
    }

    public void upload(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
        var vertexBuffers = buffer.getVertexBuffers();

        if (buffer.indexOnly) {
            long paramsPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), QuadFacing.UNDEFINED.ordinal());

            int firstIndex = DrawParametersBuffer.getFirstIndex(paramsPtr);
            int indexCount = DrawParametersBuffer.getIndexCount(paramsPtr);

            int oldOffset = indexCount > 0 ? firstIndex : -1;
            AreaBuffer.Segment segment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramsPtr);
            firstIndex = segment.offset / INDEX_SIZE;

            DrawParametersBuffer.setFirstIndex(paramsPtr, firstIndex);

            buffer.release();
            return;
        }

        int oldOffset = -1;
        int size = 0;
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);
            int vertexOffset = DrawParametersBuffer.getVertexOffset(paramPtr);

            // Only need to get first used offset, as it identifies the whole segment that will be freed
            if (oldOffset == -1) {
                oldOffset = vertexOffset;
            }

            var vertexBuffer = vertexBuffers[i];
            if (vertexBuffer != null) {
                size += vertexBuffer.remaining();

            }
        }

        AreaBuffer areaBuffer = null;
        AreaBuffer.Segment segment = null;
        boolean doUpload = false;
        if (size > 0) {
            areaBuffer = this.getAreaBufferOrAlloc(renderType);
            areaBuffer.freeSegment(oldOffset);
            segment = areaBuffer.allocateSegment(size);
            doUpload = true;
        }

        int baseInstance = section.inAreaIndex;

        int offset = 0;
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);

            int vertexOffset = -1;
            int firstIndex = 0;
            int indexCount = 0;

            var vertexBuffer = vertexBuffers[i];
            int vertexCount = 0;

            if (vertexBuffer != null && doUpload) {
                areaBuffer.upload(segment, vertexBuffer, offset);
                vertexOffset = (segment.offset + offset) / vertexSize;

                offset += vertexBuffer.remaining();
                vertexCount = vertexBuffer.limit() / vertexSize;
                indexCount = vertexCount * 6 / 4;
            }

            if (i == QuadFacing.UNDEFINED.ordinal() && !buffer.autoIndices) {
                if (this.indexBuffer == null) {
                    this.indexBuffer = new AreaBuffer(AreaBuffer.Usage.INDEX, 60000, INDEX_SIZE);
                }

                oldOffset = DrawParametersBuffer.getIndexCount(paramPtr) > 0 ? DrawParametersBuffer.getFirstIndex(paramPtr) : -1;
                AreaBuffer.Segment ibSegment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramPtr);
                firstIndex = ibSegment.offset / INDEX_SIZE;
            } else {
                Renderer.getDrawer().getQuadsIndexBuffer().checkCapacity(vertexCount);
            }

            DrawParametersBuffer.setIndexCount(paramPtr, indexCount);
            DrawParametersBuffer.setFirstIndex(paramPtr, firstIndex);
            DrawParametersBuffer.setVertexOffset(paramPtr, vertexOffset);
            DrawParametersBuffer.setBaseInstance(paramPtr, baseInstance);
        }

        updateUniformData(section);

        buffer.release();
    }

    private void updateUniformData(RenderSection section) {
        int encodedOffset = encodeSectionOffset(section.xOffset(), section.yOffset(), section.zOffset());
        int ptrOffset = section.inAreaIndex * 4;
        MemoryUtil.memPutInt(sectionDataBuffer.getPointer() + ptrOffset, encodedOffset);

        if (section.getCompiledSection() == CompiledSection.UNCOMPILED) {
            long buildTime = System.currentTimeMillis();
            this.buildTimes[section.inAreaIndex] = buildTime;

            if (buildTime > this.latestBuildTime) {
                this.latestBuildTime = buildTime;
            }
        }
    }

    private void updateFadeUniform(long currentTime, int fadeTimeMs, float fadeTimeInv) {
        if (this.lastFadeUpdate < this.latestBuildTime + fadeTimeMs) {
            int ptrOffset = 512 * 4;
            for (int i = 0; i < 512; i++) {
                long delta = currentTime - this.buildTimes[i];
                float fade = fadeTimeMs > 0 ? Mth.clamp(delta * fadeTimeInv, 0.0f, 1.0f) : 1.0f;

                MemoryUtil.memPutFloat(sectionDataBuffer.getPointer() + ptrOffset, fade);
                ptrOffset += 4;
            }

            this.lastFadeUpdate = currentTime;
        }
    }

    private AreaBuffer getAreaBufferOrAlloc(TerrainRenderType renderType) {
        this.allocated = true;

        int initialSize = switch (renderType) {
            case SOLID -> 100000;
            case CUTOUT -> 250000;
            case TRANSLUCENT, TRIPWIRE -> 60000;
        };

        return this.vertexBuffers.computeIfAbsent(
                renderType, renderType1 -> new AreaBuffer(AreaBuffer.Usage.VERTEX, initialSize, vertexSize));
    }

    public AreaBuffer getAreaBuffer(TerrainRenderType r) {
        return this.vertexBuffers.get(r);
    }

    /** Synchronize the GPU push-constant origin with the recycled area slot. */
    public void setOrigin(int x, int y, int z) {
        this.origin.set(x, y, z);
    }

    private boolean hasRenderType(TerrainRenderType r) {
        return this.vertexBuffers.containsKey(r);
    }

    private int encodeSectionOffset(int xOffset, int yOffset, int zOffset) {
        // vm_ModelOffset already contains this area's world origin minus the
        // camera.  The per-instance section value must therefore be relative
        // to that origin; encoding absolute coordinates applies the world
        // position twice and produces the fragmented terrain seen in the
        // pack G-buffer and shadow map.
        final int xOffset1 = ((xOffset - this.origin.x) & 127);
        final int zOffset1 = ((zOffset - this.origin.z) & 127);
        final int yOffset1 = ((yOffset - this.origin.y) & 127);
        return yOffset1 << 16 | zOffset1 << 8 | xOffset1;
    }

    private void updateChunkAreaOrigin(VkCommandBuffer commandBuffer, Pipeline pipeline, double camX, double camY, double camZ, MemoryStack stack) {
        float xOffset = (float) ((this.origin.x) + positionOffset - camX);
        float yOffset = (float) ((this.origin.y) + positionOffset - camY);
        float zOffset = (float) ((this.origin.z) + positionOffset - camZ);

        // Native/Beryl terrain declares vec3; the experimental pack path uses
        // vec4. Upload only the range declared by the selected pipeline.
        var constants = pipeline.getPushConstants();
        if (constants == null || constants.getSize() < 12) {
            throw new IllegalStateException("Terrain pipeline is missing its model-offset push constants");
        }
        ByteBuffer byteBuffer = stack.calloc(constants.getSize());
        byteBuffer.putFloat(0, xOffset);
        byteBuffer.putFloat(4, yOffset);
        byteBuffer.putFloat(8, zOffset);
        vkCmdPushConstants(commandBuffer, pipeline.getLayout(), constants.stages, 0, byteBuffer);
    }

    public void buildDrawBatchesIndirect(Vector3d cameraPos, IndirectBuffer indirectBuffer, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        buildDrawBatchesIndirect(cameraPos, indirectBuffer, queue, terrainRenderType, false);
    }

    /** Build batches for a light camera. Shadow maps must not inherit the
     * player's face-culling mask, because faces hidden from the player can
     * still cast into the light camera. */
    public void buildDrawBatchesIndirect(Vector3d cameraPos, IndirectBuffer indirectBuffer,
                                         StaticQueue<RenderSection> queue,
                                         TerrainRenderType terrainRenderType,
                                         boolean noCameraCulling) {
        long bufferPtr = cmdBufferPtr;

        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        boolean backFaceCulling = !noCameraCulling && Initializer.CONFIG.backFaceCulling && !isTranslucent;

        int drawCount = 0;

        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;

        int count = 0;
        if (backFaceCulling) {
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                masks[count] = getMask(cameraPos, section);
                count++;
            }

            long ptr = bufferPtr;

            for (int j = 0; j < count; ++j) {
                final int sectionIdx = sectionIndices[j];

                int mask = masks[j];

                long drawParamsBasePtr2 = drawParamsBasePtr + (sectionIdx * facingsStride);

                int indexCount = 0;
                int firstIndex = 0;
                int vertexOffset = 0;
                int baseInstance = 0;

                for (int i = 0; i < QuadFacing.COUNT; i++) {

                    if ((mask & 1 << i) == 0) {
                        drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;

                        // Flush draw cmd
                        if (indexCount > 0) {
                            MemoryUtil.memPutInt(ptr, indexCount);
                            MemoryUtil.memPutInt(ptr + 4, 1);
                            MemoryUtil.memPutInt(ptr + 8, firstIndex);
                            MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                            MemoryUtil.memPutInt(ptr + 16, baseInstance);

                            ptr += CMD_STRIDE;
                            drawCount++;
                        }

                        indexCount = 0;
                        firstIndex = 0;
                        vertexOffset = 0;
                        baseInstance = 0;

                        continue;
                    }

                    long drawParamsPtr = drawParamsBasePtr2;

                    final int indexCount_i = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                    final int firstIndex_i = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                    final int vertexOffset_i = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                    final int baseInstance_i = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                    if (indexCount == 0) {
                        indexCount = indexCount_i;
                        firstIndex = firstIndex_i;
                        vertexOffset = vertexOffset_i;
                        baseInstance = baseInstance_i;
                    }
                    else {
                        indexCount += indexCount_i;
                    }

                    drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;
                }

                if (indexCount > 0) {
                    MemoryUtil.memPutInt(ptr, indexCount);
                    MemoryUtil.memPutInt(ptr + 4, 1);
                    MemoryUtil.memPutInt(ptr + 8, firstIndex);
                    MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                    MemoryUtil.memPutInt(ptr + 16, baseInstance);

                    ptr += CMD_STRIDE;
                    drawCount++;
                }
            }

        }
        else {
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                count++;
            }

            final long facingOffset = UNDEFINED_FACING_IDX * DrawParametersBuffer.STRIDE;
            drawParamsBasePtr += facingOffset;

            long ptr = bufferPtr;
            for (int i = 0; i < count; ++i) {
                int sectionIdx = sectionIndices[i];

                long drawParamsPtr = drawParamsBasePtr + (sectionIdx * facingsStride);

                final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                final int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                final int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                final int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                if (indexCount <= 0) {
                    continue;
                }

                MemoryUtil.memPutInt(ptr, indexCount);
                MemoryUtil.memPutInt(ptr + 4, 1);
                MemoryUtil.memPutInt(ptr + 8, firstIndex);
                MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                MemoryUtil.memPutInt(ptr + 16, baseInstance);

                ptr += CMD_STRIDE;
                drawCount++;
            }
        }

        if (drawCount == 0) {
            return;
        }

        ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(cmdBufferPtr, queue.size() * QuadFacing.COUNT * CMD_STRIDE);
        indirectBuffer.recordCopyCmd(byteBuffer.position(0));

        vkCmdDrawIndexedIndirect(Renderer.getCommandBuffer(), indirectBuffer.getId(), indirectBuffer.getOffset(), drawCount, CMD_STRIDE);
    }

    public void buildDrawBatchesDirect(Vector3d cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        buildDrawBatchesDirect(cameraPos, queue, terrainRenderType, false);
    }

    public void buildDrawBatchesDirect(Vector3d cameraPos, StaticQueue<RenderSection> queue,
                                       TerrainRenderType terrainRenderType,
                                       boolean noCameraCulling) {
        buildDrawBatchesDirect(cameraPos, queue, terrainRenderType, noCameraCulling, false);
    }

    /**
     * Pack G-buffer draws use gl_InstanceIndex to select the section offset.
     * The normal direct path coalesces adjacent sections into one indexed draw
     * and therefore reuses the first section's baseInstance for all vertices.
     * Keep one draw per section/facing when the shader consumes section data.
     */
    public void buildDrawBatchesDirectPack(Vector3d cameraPos, StaticQueue<RenderSection> queue,
                                           TerrainRenderType terrainRenderType,
                                           boolean noCameraCulling) {
        buildDrawBatchesDirect(cameraPos, queue, terrainRenderType, noCameraCulling, true);
    }

    /** Log the exact first-instance/section contract consumed by a pack
     * shadow draw. This is intentionally opt-in because it runs on the
     * render thread and is only useful while diagnosing caster placement. */
    public boolean debugPackShadowDraw(RenderSection section, TerrainRenderType terrainRenderType) {
        if (!Boolean.getBoolean("vulkanmod.debugShadowDraws")) return false;
        long frame = PackDebug.frameNumber();
        if (lastShadowDrawDiagnostic == frame) return true;
        lastShadowDrawDiagnostic = frame;

        long params = 0L;
        for (int facing = 0; facing < DrawParametersBuffer.FACINGS; facing++) {
            long candidate = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr,
                    section.inAreaIndex, terrainRenderType.ordinal(), facing);
            if (DrawParametersBuffer.getIndexCount(candidate) > 0) {
                params = candidate;
                break;
            }
        }
        if (params == 0L) return false;
        int encoded = MemoryUtil.memGetInt(sectionDataBuffer.getPointer() + (long) section.inAreaIndex * 4L);
        int decodedX = encoded & 0xFF;
        int decodedZ = (encoded >>> 8) & 0xFF;
        int decodedY = (encoded >>> 16) & 0xFF;
        Initializer.LOGGER.info("VulkanMod SHADOW_DRAW: section=({}, {}, {}) area=({}, {}, {}) "
                        + "encoded=0x{} decoded=({}, {}, {}) baseInstance={} vertexOffset={} firstIndex={} indexCount={} stride={}",
                section.xOffset, section.yOffset, section.zOffset,
                this.origin.x, this.origin.y, this.origin.z,
                Integer.toHexString(encoded), decodedX, decodedY, decodedZ,
                DrawParametersBuffer.getBaseInstance(params),
                DrawParametersBuffer.getVertexOffset(params),
                DrawParametersBuffer.getFirstIndex(params),
                DrawParametersBuffer.getIndexCount(params), this.vertexSize);
        return true;
    }

    private void buildDrawBatchesDirect(Vector3d cameraPos, StaticQueue<RenderSection> queue,
                                        TerrainRenderType terrainRenderType,
                                        boolean noCameraCulling, boolean perSection) {
        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        boolean backFaceCulling = !noCameraCulling && Initializer.CONFIG.backFaceCulling && !isTranslucent;

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        if (perSection) {
            long paramsBase = this.drawParamsPtr
                    + (long) terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS
                    * DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;
            long facingsStride = (long) DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                RenderSection section = iterator.next();
                // Solid/cutout builders store quads in their six directional
                // facing buffers when back-face culling is enabled. A light
                // pass disables camera culling, but must still submit those
                // directional buffers; selecting only UNDEFINED here skips
                // almost all terrain casters and leaves an empty shadow map.
                int mask = noCameraCulling
                        ? ((1 << QuadFacing.COUNT) - 1)
                        : (backFaceCulling ? getMask(cameraPos, section) : (1 << UNDEFINED_FACING_IDX));
                long sectionBase = paramsBase + (long) section.inAreaIndex * facingsStride;
                for (int facing = 0; facing < DrawParametersBuffer.FACINGS; facing++) {
                    if ((mask & (1 << facing)) == 0) continue;
                    long params = sectionBase + (long) facing * DrawParametersBuffer.STRIDE;
                    int indexCount = DrawParametersBuffer.getIndexCount(params);
                    if (indexCount <= 0) continue;
                    vkCmdDrawIndexed(commandBuffer, indexCount, 1,
                            DrawParametersBuffer.getFirstIndex(params),
                            DrawParametersBuffer.getVertexOffset(params),
                            DrawParametersBuffer.getBaseInstance(params));
                }
            }
            return;
        }

        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;

        int count = 0;
        if (backFaceCulling) {
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                masks[count] = getMask(cameraPos, section);
                count++;
            }

            for (int j = 0; j < count; ++j) {
                final int sectionIdx = sectionIndices[j];

                int mask = masks[j];

                long drawParamsBasePtr2 = drawParamsBasePtr + (sectionIdx * facingsStride);

                int indexCount   = 0;
                int firstIndex   = 0;
                int vertexOffset = 0;
                int baseInstance = 0;

                for (int i = 0; i < QuadFacing.COUNT; i++) {

                    if ((mask & 1 << i) == 0) {
                        drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;

                        // Flush draw cmd
                        if (indexCount > 0) {
                            vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
                        }

                        indexCount   = 0;
                        firstIndex   = 0;
                        vertexOffset = 0;
                        baseInstance = 0;

                        continue;
                    }

                    long drawParamsPtr = drawParamsBasePtr2;

                    final int indexCount_i = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                    final int firstIndex_i = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                    final int vertexOffset_i = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                    final int baseInstance_i = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                    if (indexCount == 0) {
                        indexCount   = indexCount_i;
                        firstIndex   = firstIndex_i;
                        vertexOffset = vertexOffset_i;
                        baseInstance = baseInstance_i;
                    }
                    else {
                        indexCount += indexCount_i;
                    }

                    drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;
                }

                if (indexCount > 0) {
                    vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
                }
            }

        }
        else {
            final long facingOffset = UNDEFINED_FACING_IDX * DrawParametersBuffer.STRIDE;
            drawParamsBasePtr += facingOffset;

            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                count++;
            }

            for (int i = 0; i < count; ++i) {
                int sectionIdx = sectionIndices[i];

                long drawParamsPtr = drawParamsBasePtr + (sectionIdx * facingsStride);

                final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                final int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                final int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                final int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                if (indexCount <= 0) {
                    continue;
                }

                vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
            }
        }
    }

    private int getMask(Vector3d camera, RenderSection section) {
        final int secX = section.xOffset;
        final int secY = section.yOffset;
        final int secZ = section.zOffset;

        int mask = 1 << UNDEFINED_FACING_IDX;

        mask |= camera.x - secX >= 0 ? 1 << QuadFacing.X_POS.ordinal() : 0;
        mask |= camera.y - secY >= 0 ? 1 << QuadFacing.Y_POS.ordinal() : 0;
        mask |= camera.z - secZ >= 0 ? 1 << QuadFacing.Z_POS.ordinal() : 0;
        mask |= camera.x - (secX + 16) < 0 ? 1 << QuadFacing.X_NEG.ordinal() : 0;
        mask |= camera.y - (secY + 16) < 0 ? 1 << QuadFacing.Y_NEG.ordinal() : 0;
        mask |= camera.z - (secZ + 16) < 0 ? 1 << QuadFacing.Z_NEG.ordinal() : 0;

        return mask;
    }

    public void bindBuffers(VkCommandBuffer commandBuffer, Pipeline pipeline, TerrainRenderType terrainRenderType,
                            UBO sectionData,
                            double camX, double camY, double camZ,
                            long currentTime, int fadeTimeMs, float fadeTimeInv
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var vertexBuffer = getAreaBuffer(terrainRenderType);
            nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(vertexBuffer.getId()), stack.npointer(0));
            updateChunkAreaOrigin(commandBuffer, pipeline, camX, camY, camZ, stack);
        }

        this.updateFadeUniform(currentTime, fadeTimeMs, fadeTimeInv);

        sectionData.getBufferSlice().set(sectionDataBuffer, 0, (int) sectionDataBuffer.getBufferSize());

        if (terrainRenderType == TerrainRenderType.TRANSLUCENT && this.indexBuffer != null) {
            vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0, VK_INDEX_TYPE_UINT16);
        }
    }

    public void releaseBuffers() {
        if (!this.allocated)
            return;

        this.vertexBuffers.values().forEach(AreaBuffer::freeBuffer);
        this.vertexBuffers.clear();

        if (this.indexBuffer != null)
            this.indexBuffer.freeBuffer();
        this.indexBuffer = null;

        this.allocated = false;
    }

    public void free() {
        this.releaseBuffers();

        DrawParametersBuffer.freeBuffer(this.drawParamsPtr);
    }

    public boolean isAllocated() {
        return !this.vertexBuffers.isEmpty();
    }

    public EnumMap<TerrainRenderType, AreaBuffer> getVertexBuffers() {
        return vertexBuffers;
    }

    public AreaBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public long getDrawParamsPtr() {
        return drawParamsPtr;
    }

}
