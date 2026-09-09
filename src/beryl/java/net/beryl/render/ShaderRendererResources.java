package net.beryl.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexFormatElement.Type;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public class ShaderRendererResources {
   private static final VertexFormatElement ELEMENT_POSITION_FLOAT = new VertexFormatElement(0, 0, Type.FLOAT, false, 3);
   private static final VertexFormatElement ELEMENT_COLOR = new VertexFormatElement(1, 0, Type.UBYTE, true, 4);
   private static final VertexFormatElement ELEMENT_UV0_FLOAT = new VertexFormatElement(2, 0, Type.FLOAT, false, 2);
   private static final VertexFormatElement ELEMENT_UV2 = new VertexFormatElement(4, 2, Type.SHORT, false, 2);
   private static final VertexFormatElement ELEMENT_NORMAL = new VertexFormatElement(5, 0, Type.BYTE, true, 4);
   private static final VertexFormatElement ELEMENT_BLOCK_ID = new VertexFormatElement(7, 0, Type.SHORT, false, 1);
   private static final VertexFormatElement ELEMENT_BLOCK_ID_INT = new VertexFormatElement(7, 0, Type.INT, false, 1);
   private static final VertexFormatElement ELEMENT_WATER_DEPTH = new VertexFormatElement(8, 0, Type.FLOAT, false, 1);
   private static final VertexFormatElement ELEMENT_UV00 = new VertexFormatElement(2, 0, Type.FLOAT, false, 2);
   public static final VertexFormatElement ELEMENT_POSITION = new VertexFormatElement(0, 0, Type.SHORT, false, 4);
   public static final VertexFormatElement ELEMENT_COLOR_UINT = new VertexFormatElement(1, 0, Type.UINT, true, 1);
   public static final VertexFormatElement ELEMENT_UV0 = new VertexFormatElement(2, 0, Type.USHORT, false, 2);
   public static final VertexFormat EXT_BLOCK = VertexFormat.builder()
      .add("Position", ELEMENT_POSITION_FLOAT)
      .add("Color", ELEMENT_COLOR)
      .add("UV0", ELEMENT_UV0_FLOAT)
      .add("UV2", ELEMENT_UV2)
      .add("Normal", ELEMENT_NORMAL)
      .add("BlockID", ELEMENT_BLOCK_ID_INT)
      .add("WaterDepth", ELEMENT_WATER_DEPTH)
      .build();
   public static final VertexFormat BLOCK_NORMAL = VertexFormat.builder()
      .add("Position", VertexFormatElement.POSITION)
      .add("Color", VertexFormatElement.COLOR)
      .add("UV0", VertexFormatElement.UV0)
      .add("UV2", ELEMENT_UV2)
      .add("Normal", ELEMENT_NORMAL)
      .build();
   public static final VertexFormat EXT_COMPRESSED_TERRAIN_FORMAT = VertexFormat.builder()
      .add("Position", ELEMENT_POSITION)
      .add("UV0", ELEMENT_UV0)
      .add("Color", ELEMENT_COLOR_UINT)
      .add("Normal", ELEMENT_NORMAL)
      .add("BlockID", ELEMENT_BLOCK_ID_INT)
      .add("WaterDepth", ELEMENT_WATER_DEPTH)
      .build();
   private static volatile boolean initialized = false;
   private static final Object2IntOpenHashMap<Block> blockIdMap = new Object2IntOpenHashMap();
   private static final Object2IntOpenHashMap<Fluid> fluidIdMap = new Object2IntOpenHashMap();

   static {
      initBlockIdMap();
   }

   public static synchronized void initBlockIdMap() {
      if (initialized) return;
      blockIdMap.defaultReturnValue(-1);
      fluidIdMap.defaultReturnValue(-1);
      blockIdMap.put(Blocks.WATER, 1);
      blockIdMap.put(Blocks.WHITE_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.ORANGE_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.MAGENTA_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.LIGHT_BLUE_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.YELLOW_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.LIME_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.PINK_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.GRAY_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.LIGHT_GRAY_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.CYAN_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.PURPLE_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.BLUE_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.BROWN_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.GREEN_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.RED_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.BLACK_STAINED_GLASS, 2);
      blockIdMap.put(Blocks.IRON_BLOCK, 3);
      blockIdMap.put(Blocks.GOLD_BLOCK, 4);
      blockIdMap.put(Blocks.COPPER_BLOCK, 5);
      int id = 10;
      blockIdMap.put(Blocks.GRASS_BLOCK, id);
      blockIdMap.put(Blocks.OAK_LEAVES, id);
      blockIdMap.put(Blocks.ACACIA_LEAVES, id);
      blockIdMap.put(Blocks.BIRCH_LEAVES, id);
      blockIdMap.put(Blocks.JUNGLE_LEAVES, id);
      blockIdMap.put(Blocks.SPRUCE_LEAVES, id);
      blockIdMap.put(Blocks.DIRT, 11);
      blockIdMap.put(Blocks.SAND, 12);
      int var1 = 13;
      blockIdMap.put(Blocks.SHORT_GRASS, var1);
      blockIdMap.put(Blocks.TALL_GRASS, var1);
      blockIdMap.put(Blocks.FERN, var1);
      blockIdMap.put(Blocks.LARGE_FERN, var1);
      blockIdMap.put(Blocks.BUSH, var1);
      blockIdMap.put(Blocks.SWEET_BERRY_BUSH, var1);
      blockIdMap.put(Blocks.SEAGRASS, var1);
      blockIdMap.put(Blocks.DANDELION, var1);
      blockIdMap.put(Blocks.AZURE_BLUET, var1);
      blockIdMap.put(Blocks.PEONY, var1);
      blockIdMap.put(Blocks.OXEYE_DAISY, var1);
      blockIdMap.put(Blocks.CORNFLOWER, var1);
      blockIdMap.put(Blocks.POPPY, var1);
      blockIdMap.put(Blocks.LILAC, var1);
      blockIdMap.put(Blocks.ROSE_BUSH, var1);
      blockIdMap.put(Blocks.LILY_OF_THE_VALLEY, var1);
      blockIdMap.put(Blocks.SUGAR_CANE, var1);
      blockIdMap.put(Blocks.LAVA, 14);
      blockIdMap.put(Blocks.FIRE, 14);
      var1 = 15;
      blockIdMap.put(Blocks.CAMPFIRE, var1);
      blockIdMap.put(Blocks.SOUL_CAMPFIRE, var1);
      blockIdMap.put(Blocks.TORCH, var1);
      blockIdMap.put(Blocks.WALL_TORCH, var1);
      blockIdMap.put(Blocks.MAGMA_BLOCK, var1);
      blockIdMap.put(Blocks.LANTERN, var1);
      blockIdMap.put(Blocks.SOUL_LANTERN, var1);
      blockIdMap.put(Blocks.SEA_LANTERN, var1);
      blockIdMap.put(Blocks.REDSTONE_TORCH, 15);
      blockIdMap.put(Blocks.REDSTONE_WALL_TORCH, 15);
      blockIdMap.put(Blocks.REDSTONE_LAMP, 15);
      blockIdMap.put(Blocks.GLOWSTONE, 16);
      blockIdMap.put(Blocks.CRYING_OBSIDIAN, 16);
      blockIdMap.put(Blocks.OBSIDIAN, 20);
      fluidIdMap.put(Fluids.WATER, 1);
      fluidIdMap.put(Fluids.FLOWING_WATER, 1);
      fluidIdMap.put(Fluids.LAVA, 14);
      fluidIdMap.put(Fluids.FLOWING_LAVA, 14);
      initialized = true;
   }

   public static int getBlockId(BlockState blockState) {
      if (blockState == null) return -1;
      Block block = blockState.getBlock();
      return block != null ? blockIdMap.getInt(block) : -1;
   }

   public static int getBlockId(FluidState fluidState) {
      if (fluidState == null) return -1;
      Fluid fluid = fluidState.getType();
      return fluid != null ? fluidIdMap.getInt(fluid) : -1;
   }
}
