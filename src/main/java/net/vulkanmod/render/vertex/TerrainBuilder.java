package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class TerrainBuilder {
    private static final Logger LOGGER = Initializer.LOGGER;
    private static final MemoryUtil.MemoryAllocator ALLOCATOR = MemoryUtil.getAllocator(false);

    protected long indexBufferPtr;

    private int indexBufferCapacity;

    private final VertexFormat format;

    private boolean building;

    private final QuadSorter quadSorter = new QuadSorter();

    private boolean needsSorting;
    private boolean indexOnly;

    protected VertexBuilder vertexBuilder;

    private final TerrainBufferBuilder[] bufferBuilders;
    private int packEntity;
    private float packMidU;
    private float packMidV;

    public TerrainBuilder(int size, VertexBuilder vertexBuilder) {
        // FIXME: same size is used for both index and vertex buffers
        this.indexBufferCapacity = size;
        this.indexBufferPtr = ALLOCATOR.malloc(this.indexBufferCapacity);

        this.format = PipelineManager.terrainVertexFormat;
        this.vertexBuilder = vertexBuilder;

        var bufferBuilders = new TerrainBufferBuilder[QuadFacing.COUNT];
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            bufferBuilders[i] = new TerrainBufferBuilder(size, this.format.getVertexSize(), this.vertexBuilder);
        }

        this.bufferBuilders = bufferBuilders;
    }

    public TerrainBufferBuilder getBufferBuilder(int i) {
        this.bufferBuilders[i].setPackMaterial(this.packEntity, this.packMidU, this.packMidV);
        return this.bufferBuilders[i];
    }

    private void ensureIndexCapacity(int size) {
        if (size > this.indexBufferCapacity) {
            int capacity = this.indexBufferCapacity;
            int newSize = (capacity + size) * 2;
            this.resizeIndexBuffer(newSize);
        }
    }

    private void resizeIndexBuffer(int i) {
        this.indexBufferPtr = ALLOCATOR.realloc(this.indexBufferPtr, i);
        LOGGER.debug("Needed to grow index buffer: Old size {} bytes, new size {} bytes.", this.indexBufferCapacity, i);
        if (this.indexBufferPtr == 0L) {
            throw new OutOfMemoryError("Failed to resize buffer from " + this.indexBufferCapacity + " bytes to " + i + " bytes");
        } else {
            this.indexBufferCapacity = i;
        }
    }

    public void setupQuadSorting(float x, float y, float z) {
        this.quadSorter.setQuadSortOrigin(x, y, z);
        this.needsSorting = true;
    }

    public QuadSorter.SortState getSortState() {
        return this.quadSorter.getSortState();
    }

    public void restoreSortState(QuadSorter.SortState sortState) {
        this.quadSorter.restoreSortState(sortState);

        this.indexOnly = true;
    }

    public void setIndexOnly() {
        this.indexOnly = true;
    }

    public void begin() {
        if (this.building) {
            throw new IllegalStateException("Already building!");
        } else {
            this.building = true;
        }
    }

    public void setupQuadSortingPoints() {
        TerrainBufferBuilder bufferBuilder = bufferBuilders[QuadFacing.UNDEFINED.ordinal()];
        long bufferPtr = bufferBuilder.getPtr();
        int vertexCount = bufferBuilder.getVertices();

        this.quadSorter.setupQuadSortingPoints(bufferPtr, vertexCount, this.format);
    }

    public DrawState endDrawing() {
        for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
            bufferBuilder.end();
        }

        int vertexCount = this.quadSorter.getVertexCount();
        // Solid and cutout terrain do not initialize the quad sorter because
        // they do not need translucent quad sorting. Their vertex count still
        // needs to be carried into the draw state so the per-facing buffers
        // can be uploaded and indexed.
        if (vertexCount == 0) {
            for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
                vertexCount += bufferBuilder.getVertices();
            }
        }

        int indexCount = vertexCount / 4 * 6;

        VertexFormat.IndexType indexType = VertexFormat.IndexType.least(indexCount);
        boolean sequentialIndexing;

        // TODO sorting
        if (this.needsSorting) {
            int indexBufferSize = indexCount * indexType.bytes;
            this.ensureIndexCapacity(indexBufferSize);

            this.quadSorter.putSortedQuadIndices(this, indexType);

            sequentialIndexing = false;
        } else {
            sequentialIndexing = true;
        }

        return new DrawState(this.format.getVertexSize(), indexCount, indexType, this.indexOnly, sequentialIndexing);
    }

    // TODO hardcoded index type size
    public ByteBuffer getIndexBuffer() {
        int indexCount = this.quadSorter.getVertexCount() * 6 / 4;

        return MemoryUtil.memByteBuffer(this.indexBufferPtr, indexCount * 2);
    }

    private void ensureDrawing() {
        if (!this.building) {
            throw new IllegalStateException("Not building!");
        }
    }

    public void reset() {
        this.building = false;

        this.indexOnly = false;
        this.needsSorting = false;
    }

    public void clear() {
        this.reset();

        for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
            bufferBuilder.clear();
        }
    }

    public void free() {
        ALLOCATOR.free(this.indexBufferPtr);

        for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
            bufferBuilder.free();
        }
    }

    public void setBlockAttributes(BlockState blockState) {
        this.packEntity = materialId(blockState);
        this.packMidU = 0.0f;
        this.packMidV = 0.0f;
    }

    public void setFluidBlockAttributes(FluidState fluidState) {
        if (fluidState != null && !fluidState.isEmpty()) {
            if (fluidState.is(FluidTags.WATER)) this.packEntity = 32000;
            else if (fluidState.is(FluidTags.LAVA)) this.packEntity = 32032;
        }
    }

    /**
     * Complementary uses the OptiFine/Iris mc_Entity channel as a material
     * ABI, not as a generic block id.  Supplying zero for every translucent
     * quad makes water, glass, leaves and vines all take the wrong lighting
     * path (and also prevents their correct shadow policy).  Keep this table
     * deliberately based on stable block description ids so modded blocks do
     * not require hard dependencies on their classes.
     */
    private static int materialId(BlockState state) {
        if (state == null) return 0;
        if (state.getFluidState().is(FluidTags.WATER)) return 32000;
        if (state.getFluidState().is(FluidTags.LAVA)) return 32032;

        String id = state.getBlock().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("glass_pane") || id.contains("iron_bars") || id.contains("pane")) return 32012;
        if (id.contains("glass")) return 32008;
        if (id.contains("ice")) return 32004;
        if (id.contains("vine")) return 10013;
        if (id.contains("leaves") || id.contains("leaf")) return 10009;
        if (id.contains("grass") || id.contains("fern") || id.contains("flower")
                || id.contains("bush") || id.contains("sapling") || id.contains("crop")
                || id.contains("kelp") || id.contains("seagrass") || id.contains("fungus")) {
            return 10005;
        }
        return 0;
    }

    public record DrawState(int vertexSize, int indexCount, VertexFormat.IndexType indexType,
                            boolean indexOnly, boolean sequentialIndex) {

        private int indexBufferSize() {
            return this.sequentialIndex ? 0 : this.indexCount * this.indexType.bytes;
        }

        public int indexCount() {
            return this.indexCount;
        }

        public VertexFormat.IndexType indexType() {
            return this.indexType;
        }

        public boolean indexOnly() {
            return this.indexOnly;
        }

        public boolean sequentialIndex() {
            return this.sequentialIndex;
        }
    }
}
