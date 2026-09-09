package net.vulkanmod.shaders;

import net.vulkanmod.Initializer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PackDebug {
    private static final int DUMP_EVERY = 240;

    private static long frameCounter = 0;
    private static long lastUniformLogFrame = -1;
    private static volatile int terrainAreas = -1;
    private static volatile int terrainDrawn = -1;
    private static volatile int terrainNoBuffer = -1;
    private static volatile int terrainEmptyQueue = -1;
    private static volatile int shadowDrawn = -1;
    private static volatile int entityShadowCollections = -1;
    private static volatile int entityShadowDraws = -1;
    private static volatile int resourceFallbacks = 0;
    private static final Set<String> reportedResourceFallbacks = ConcurrentHashMap.newKeySet();

    private PackDebug() {}

    public static void frame() {
        frameCounter++;
    }

    public static long frameNumber() { return frameCounter; }

    public static boolean shouldLog() {
        return frameCounter % DUMP_EVERY == 0;
    }

    /** Prevent multi-pass uniform updates from repeating the same log burst. */
    public static boolean shouldLogUniforms() {
        if (!shouldLog() || lastUniformLogFrame == frameCounter) return false;
        lastUniformLogFrame = frameCounter;
        return true;
    }

    public static void log(String fmt, Object... args) {
        if (shouldLog()) {
            Initializer.LOGGER.info("[packdbg #" + frameCounter + "] " + fmt.formatted(args));
        }
    }

    public static void setTerrainStats(int areas, int drawn, int noBuffer, int emptyQueue) {
        terrainAreas = areas;
        terrainDrawn = drawn;
        terrainNoBuffer = noBuffer;
        terrainEmptyQueue = emptyQueue;
    }

    public static int getTerrainAreas() { return terrainAreas; }
    public static int getTerrainDrawn() { return terrainDrawn; }
    public static int getTerrainNoBuffer() { return terrainNoBuffer; }
    public static int getTerrainEmptyQueue() { return terrainEmptyQueue; }

    public static void setShadowDrawn(int drawn) { shadowDrawn = drawn; }
    public static int getShadowDrawn() { return shadowDrawn; }

    public static void setEntityShadowCollections(int count) { entityShadowCollections = count; }
    public static int getEntityShadowCollections() { return entityShadowCollections; }

    public static void setEntityShadowDraws(int count) { entityShadowDraws = count; }
    public static int getEntityShadowDraws() { return entityShadowDraws; }

    /** Record a compatibility substitution that must not remain invisible. */
    public static void resourceFallback(String resource, String reason) {
        resourceFallbacks++;
        if (reportedResourceFallbacks.add(resource + "|" + reason)) {
            Initializer.LOGGER.warn("[packdbg] shader resource fallback: {} ({})", resource, reason);
        }
    }

    public static int getResourceFallbacks() { return resourceFallbacks; }

    public static String terrainSummary() {
        return "terrain areas=%d drawn=%d noBuffer=%d emptyQueue=%d"
                .formatted(terrainAreas, terrainDrawn, terrainNoBuffer, terrainEmptyQueue);
    }

    public static String shadowSummary() {
        return "shadow drawn=%d entityCollections=%d entityDraws=%d"
                .formatted(shadowDrawn, entityShadowCollections, entityShadowDraws);
    }
}
