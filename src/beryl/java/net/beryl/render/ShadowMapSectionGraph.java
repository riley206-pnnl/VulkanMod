package net.beryl.render;

import net.beryl.BerylMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.ResettableQueue;
import net.vulkanmod.render.profiling.Profiler;
import org.joml.Vector3d;

public class ShadowMapSectionGraph {
   Minecraft minecraft;
   private final Level level;
   private final SectionGrid sectionGrid;
   private final ChunkAreaManager chunkAreaManager;
   private final TaskDispatcher taskDispatcher;
   private final ResettableQueue<RenderSection> sectionQueue = new ResettableQueue();
   private AreaSetQueue chunkAreaQueue;
   private short lastFrame = 0;
   private final ResettableQueue<RenderSection> blockEntitiesSections = new ResettableQueue();
   private final ResettableQueue<RenderSection> rebuildQueue = new ResettableQueue();
   private VFrustum frustum;
   public RenderRegionBuilder renderRegionCache;
   int nonEmptyChunks;

   public ShadowMapSectionGraph(Level level, SectionGrid sectionGrid, TaskDispatcher taskDispatcher) {
      this.level = level;
      this.sectionGrid = sectionGrid;
      this.chunkAreaManager = sectionGrid.getChunkAreaManager();
      this.taskDispatcher = taskDispatcher;
      this.chunkAreaQueue = new AreaSetQueue(sectionGrid.getChunkAreaManager().size);
      this.minecraft = Minecraft.getInstance();
      this.renderRegionCache = WorldRenderer.getInstance().renderRegionCache;
   }

   public void update(Camera camera, VFrustum frustum, boolean spectator) {
      Profiler profiler = Profiler.getMainProfiler();
      profiler.push("shadow frustum update");
      this.frustum = frustum;
      this.initUpdate();

      RenderSection[] sections = this.sectionGrid.sections;
      if (sections != null) {
         for (int i = 0; i < sections.length; i++) {
            RenderSection renderSection = sections[i];
            if (renderSection == null || renderSection.isCompletelyEmpty()) {
               continue;
            }

            if (!this.notInFrustum(renderSection)) {
               ChunkArea area = renderSection.getChunkArea();
               if (area != null) {
                  area.shadowSectionQueue.add(renderSection);
                  this.chunkAreaQueue.add(area);
                  this.nonEmptyChunks++;
               }

               if (renderSection.containsBlockEntities()) {
                  this.blockEntitiesSections.ensureCapacity(1);
                  this.blockEntitiesSections.add(renderSection);
               }

               if (renderSection.isDirty()) {
                  this.rebuildQueue.ensureCapacity(1);
                  this.rebuildQueue.add(renderSection);
               }
            }
         }
      }

      this.scheduleRebuilds();
      profiler.pop();
   }

   private void initializeQueueForFullUpdate(Camera camera) {
      Vec3 vec3 = camera.position();
      BlockPos blockpos = camera.blockPosition();
      float xDir = RenderingPipeline.lightDirWS.x();
      float yDir = RenderingPipeline.lightDirWS.y();
      float zDir = RenderingPipeline.lightDirWS.z();
      float dist = BerylMod.CONFIG.shadowRenderDistance;
      dist = Math.min(dist, 24.0F);
      int xOffset = (int)(xDir * dist * 16.0F);
      int yOffset = 64;
      int zOffset = (int)(zDir * dist * 16.0F);
      int x = blockpos.getX() + xOffset;
      int y = blockpos.getY() + yOffset;
      int z = blockpos.getZ() + zOffset;

      for (int x1 = -2; x1 <= 2; x1++) {
         for (int z1 = -2; z1 <= 2; z1++) {
            RenderSection renderSection1 = this.sectionGrid
               .getSectionAtBlockPos(x + SectionPos.sectionToBlockCoord(x1, 8), y, z + SectionPos.sectionToBlockCoord(z1, 8));
            if (renderSection1 != null) {
               initFirstNode(renderSection1, this.lastFrame);
               this.sectionQueue.add(renderSection1);
            }
         }
      }
   }

   private static void initFirstNode(RenderSection renderSection, short frame) {
      renderSection.mainDir = 7;
      renderSection.sourceDirs = -128;
      renderSection.directions = -1;
      renderSection.setLastFrame2(frame);
      renderSection.visibility = renderSection.visibility | initVisibility();
      renderSection.directionChanges = 0;
      renderSection.steps = 0;
   }

   private static long initVisibility() {
      long vis = 0L;

      for (int dir = 0; dir < 6; dir++) {
         vis |= 1L << 48 + dir;
         vis |= 1L << 56 + dir;
      }

      return vis;
   }

   private void initUpdate() {
      this.resetUpdateQueues();
      this.lastFrame++;
      this.nonEmptyChunks = 0;
   }

   private void resetUpdateQueues() {
      this.chunkAreaQueue.clear();
      this.sectionGrid.getChunkAreaManager().resetShadowQueues();
      this.sectionQueue.clear();
      this.blockEntitiesSections.clear();
      this.rebuildQueue.clear();
   }

   private void updateRenderChunks() {
      int maxDirectionsChanges = Initializer.CONFIG.advCulling - 1;

      while (this.sectionQueue.hasNext()) {
         RenderSection renderSection = (RenderSection)this.sectionQueue.poll();
         if (!this.notInFrustum(renderSection)) {
            if (!renderSection.isCompletelyEmpty()) {
               renderSection.getChunkArea().sectionQueue.add(renderSection);
               this.chunkAreaQueue.add(renderSection.getChunkArea());
               this.nonEmptyChunks++;
            }

            if (renderSection.containsBlockEntities()) {
               this.blockEntitiesSections.ensureCapacity(1);
               this.blockEntitiesSections.add(renderSection);
            }

            if (renderSection.isDirty()) {
               this.rebuildQueue.ensureCapacity(1);
               this.rebuildQueue.add(renderSection);
            }

            byte dirs = (byte)(renderSection.getVisibilityDirs() & renderSection.getDirections());
            this.visitAdjacentNodes(renderSection, dirs);
         }
      }
   }

   private void scheduleRebuilds() {
      if (WorldRenderer.getCameraPos() != null) {
         for (int i = 0; i < this.rebuildQueue.size(); i++) {
            RenderSection section = (RenderSection)this.rebuildQueue.get(i);
            Vector3d cameraPos = WorldRenderer.getCameraPos();
            if (section.rebuildChunkAsync(this.taskDispatcher, this.renderRegionCache, cameraPos)) {
               section.setNotDirty();
            }
         }

         this.rebuildQueue.clear();
      }
   }

   private boolean notInFrustum(RenderSection renderSection) {
      return !this.frustum
         .testFrustum(
            renderSection.xOffset,
            renderSection.yOffset,
            renderSection.zOffset,
            renderSection.xOffset + 16,
            renderSection.yOffset + 16,
            renderSection.zOffset + 16
         );
   }

   private void visitAdjacentNodes(RenderSection renderSection, byte dirs) {
      dirs = (byte)(dirs & renderSection.adjDirs);
      this.sectionQueue.ensureCapacity(6);
      RenderSection relativeSection = renderSection.adjDown;
      this.checkToAdd(renderSection, relativeSection, (byte)0, (byte)1, dirs);
      relativeSection = renderSection.adjUp;
      this.checkToAdd(renderSection, relativeSection, (byte)1, (byte)0, dirs);
      relativeSection = renderSection.adjNorth;
      this.checkToAdd(renderSection, relativeSection, (byte)2, (byte)3, dirs);
      relativeSection = renderSection.adjSouth;
      this.checkToAdd(renderSection, relativeSection, (byte)3, (byte)2, dirs);
      relativeSection = renderSection.adjWest;
      this.checkToAdd(renderSection, relativeSection, (byte)4, (byte)5, dirs);
      relativeSection = renderSection.adjEast;
      this.checkToAdd(renderSection, relativeSection, (byte)5, (byte)4, dirs);
   }

   private void checkToAdd(RenderSection renderSection, RenderSection relativeSection, byte dir, byte opposite, byte dirs) {
      if ((dirs & 1 << dir) != 0) {
         this.addNode(renderSection, relativeSection, dir, opposite);
      }
   }

   private void updateRenderChunksSpectator() {
      while (this.sectionQueue.hasNext()) {
         RenderSection renderSection = (RenderSection)this.sectionQueue.poll();
         if (!this.notInFrustum(renderSection)) {
            if (!renderSection.isCompletelyEmpty()) {
               renderSection.getChunkArea().sectionQueue.add(renderSection);
               this.chunkAreaQueue.add(renderSection.getChunkArea());
               this.nonEmptyChunks++;
            }

            if (renderSection.isDirty()) {
               this.rebuildQueue.ensureCapacity(1);
               this.rebuildQueue.add(renderSection);
            }

            byte dirs = (byte)(renderSection.adjDirs & renderSection.getDirections());
            this.visitAdjacentNodes(renderSection, dirs);
         }
      }
   }

   private void addNode(RenderSection renderSection, RenderSection relativeSection, byte direction, byte opposite) {
      if (relativeSection.getLastFrame2() != this.lastFrame) {
         relativeSection.setLastFrame2(this.lastFrame);
         relativeSection.mainDir = direction;
         relativeSection.sourceDirs = (byte)(1 << direction);
         relativeSection.directions = (byte)(renderSection.directions & ~(1 << opposite));
         this.sectionQueue.add(relativeSection);
      }

      relativeSection.addDir(direction);
   }

   public AreaSetQueue getChunkAreaQueue() {
      return this.chunkAreaQueue;
   }

   public ResettableQueue<RenderSection> getSectionQueue() {
      return this.sectionQueue;
   }

   public ResettableQueue<RenderSection> getBlockEntitiesSections() {
      return this.blockEntitiesSections;
   }

   public short getLastFrame() {
      return this.lastFrame;
   }

   public String getStatistics() {
      int totalSections = this.sectionGrid.getSectionCount();
      int sections = this.sectionQueue.size();
      int renderDistance = WorldRenderer.getInstance().getRenderDistance();
      String tasksInfo = this.taskDispatcher == null ? "null" : this.taskDispatcher.getStats();
      return String.format("Chunks: %d(%d)/%d D: %d, %s", this.nonEmptyChunks, sections, totalSections, renderDistance, tasksInfo);
   }
}
