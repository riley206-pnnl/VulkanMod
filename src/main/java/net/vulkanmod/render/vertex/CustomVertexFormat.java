package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class CustomVertexFormat {
    public static final VertexFormatElement ELEMENT_POSITION_INT16 = new VertexFormatElement(0, 0, VertexFormatElement.Type.SHORT, false, 4);
    public static final VertexFormatElement ELEMENT_COLOR_UINT = new VertexFormatElement(1, 0, VertexFormatElement.Type.UINT, false, 1);
    public static final VertexFormatElement ELEMENT_UV0_UINT16 = new VertexFormatElement(2, 0, VertexFormatElement.Type.USHORT, false, 2);
    public static final VertexFormatElement ELEMENT_PACK_ENTITY = new VertexFormatElement(2, 0, VertexFormatElement.Type.FLOAT, false, 4);
    public static final VertexFormatElement ELEMENT_PACK_MID_TEX = new VertexFormatElement(2, 0, VertexFormatElement.Type.FLOAT, false, 4);

    private static float POSITION_OFFSET = 4.0f;

    public static final VertexFormat COMPRESSED_TERRAIN =
            VertexFormat.builder()
                        .add("Position", ELEMENT_POSITION_INT16)
                        .add("UV0", ELEMENT_UV0_UINT16)
                        .add("Color", ELEMENT_COLOR_UINT)
                        .build();

    public static final VertexFormat TERRAIN =
            VertexFormat.builder()
                        .add("Position", VertexFormatElement.POSITION)
                        .add("Entity", ELEMENT_PACK_ENTITY)
                        .add("MidTexCoord", ELEMENT_PACK_MID_TEX)
                        .add("Color", VertexFormatElement.COLOR)
                        .add("UV0", VertexFormatElement.UV0)
                        .add("UV2", VertexFormatElement.UV2)
                        .add("Normal", VertexFormatElement.NORMAL)
                        .padding(1)
                        .build();

    public static final VertexFormatElement ELEMENT_QUAD_POS = new VertexFormatElement(0, 0, VertexFormatElement.Type.FLOAT, false, 4);
    public static final VertexFormatElement ELEMENT_QUAD_UV = new VertexFormatElement(2, 0, VertexFormatElement.Type.FLOAT, false, 4);

    public static final VertexFormat QUAD =
            VertexFormat.builder()
                        .add("Position", ELEMENT_QUAD_POS)
                        .add("UV0", ELEMENT_QUAD_UV)
                        .build();

    public static final VertexFormat NONE = VertexFormat.builder().build();

    public static void setPositionOffset(float positionOffset) {
        POSITION_OFFSET = positionOffset;
    }

    public static float getPositionOffset() {
        return POSITION_OFFSET;
    }
}
