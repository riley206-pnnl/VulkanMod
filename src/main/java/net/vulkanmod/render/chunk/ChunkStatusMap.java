package net.vulkanmod.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.level.ChunkPos;

public class ChunkStatusMap {
    public static final byte DATA_READY = 0b1;
    public static final byte LIGHT_READY = 0b10;
    public static final byte NEIGHBOURS_READY = 0b100;
    public static final byte CHUNK_READY = DATA_READY | LIGHT_READY;
    public static final byte ALL_FLAGS = CHUNK_READY | NEIGHBOURS_READY;

    public static ChunkStatusMap INSTANCE;

    public static void createInstance(int renderDistance) {
        INSTANCE = new ChunkStatusMap(renderDistance);
    }

    private final Long2ByteOpenHashMap map;
    private boolean readinessLogged;

    public ChunkStatusMap(int renderDistance) {
        int diameter = renderDistance * 2 + 1;
        map = new Long2ByteOpenHashMap(diameter * diameter);
        map.defaultReturnValue((byte) 0);
    }

    public void updateDistance(int renderDistance) {
        int diameter = renderDistance * 2 + 1;
        this.map.ensureCapacity(diameter * diameter);
    }

    public void setChunkStatus(int x, int z, byte flag) {
        long l = ChunkPos.pack(x, z);

        byte current = map.get(l);
        current |= flag;
        map.put(l, current);

        if ((current & CHUNK_READY) == CHUNK_READY)
            updateNeighbours(x, z);
    }

    public void resetChunkStatus(int x, int z, byte flag) {
        long l = ChunkPos.pack(x, z);

        byte current = map.get(l);
        current = (byte) (current & ~flag);
        map.put(l, current);

        updateNeighbours(x, z);
    }

    public void updateNeighbours(int x, int z) {
        for (int x1 = x - 1; x1 <= x + 1; ++x1) {
            for (int z1 = z - 1; z1 <= z + 1; ++z1) {
                if (checkNeighbours(x1, z1)) {
                    map.put(ChunkPos.pack(x1, z1), ALL_FLAGS);
                }
                else {
                    long l = ChunkPos.pack(x1, z1);

                    byte current = map.get(l);
                    byte n = (byte) (current & ~NEIGHBOURS_READY);

                    if (current == 0b0)
                        map.remove(l);
                    else if (current != n)
                        map.put(l, n);
                }
            }
        }
    }

    public boolean checkNeighbours(int x, int z) {
        byte flags = CHUNK_READY;
        for (int x1 = x - 1; x1 <= x + 1; ++x1) {
            for (int z1 = z - 1; z1 <= z + 1; ++z1) {
                flags &= map.get(ChunkPos.pack(x1, z1));

                if (flags != CHUNK_READY)
                    return false;
            }
        }
        return true;

//        return flags == CHUNK_READY;
    }

    public boolean chunkRenderReady(int x, int z) {
        // The client cache can briefly report a missing FULL chunk while the
        // packet callbacks have already completed DATA and LIGHT delivery.
        // That race used to strand the section rebuild queue with zero
        // scheduled tasks, leaving the shader scene as sky-only.  Accept the
        // tracked packet state as the readiness contract, while retaining the
        // cache query for worlds whose callbacks are not available.
        byte status = map.get(ChunkPos.pack(x, z));
        boolean statusReady = (status & CHUNK_READY) == CHUNK_READY;
        if (statusReady)
            return true;

        net.minecraft.world.level.Level level = WorldRenderer.getLevel();
        if (level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
            var chunkSource = clientLevel.getChunkSource();
            boolean cacheReady = chunkSource.hasChunk(x, z);
            if (Boolean.getBoolean("vulkanmod.debugChunkReadiness") && !readinessLogged) {
                readinessLogged = true;
                net.vulkanmod.Initializer.LOGGER.info(
                        "[chunkdbg] request=({}, {}) status={} statusReady={} cacheReady={} loadedChunks={}",
                        x, z, status & 0xFF, statusReady, cacheReady,
                        chunkSource.getLoadedChunksCount());
            }
            return cacheReady;
        }
        if (Boolean.getBoolean("vulkanmod.debugChunkReadiness") && !readinessLogged) {
            readinessLogged = true;
            net.vulkanmod.Initializer.LOGGER.info(
                    "[chunkdbg] request=({}, {}) status={} statusReady={} level=none",
                    x, z, status & 0xFF, statusReady);
        }
        return false;
    }

    public byte status(int x, int z) {
        return map.get(ChunkPos.pack(x, z));
    }

    public void reset() {

    }

}
