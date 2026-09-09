package net.vulkanmod.shaders;

import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public final class QuadRenderer {
    private static VertexBuffer vertexBuffer;
    private static IndexBuffer indexBuffer;

    public static synchronized void init() {
        if (vertexBuffer != null) return;

        try (MemoryStack stack = stackPush()) {
            // 4 vertices * 32 bytes (16 bytes pos, 16 bytes uv) = 128 bytes
            ByteBuffer vBuf = stack.malloc(128);
            // VulkanMod uses a negative-height viewport, so framebuffer
            // texel Y is opposite to the fullscreen quad's NDC Y.  Keep the
            // quad geometry in the usual bottom-left/top-left order, but
            // flip texture Y so a sampled render target is not presented
            // upside down (this is also the convention expected by Iris
            // composite/final shaders).
            // v0: bottom-left
            vBuf.putFloat(-1.0f).putFloat(-1.0f).putFloat(0.0f).putFloat(1.0f);
            vBuf.putFloat( 0.0f).putFloat( 1.0f).putFloat(0.0f).putFloat(1.0f);
            // v1: bottom-right
            vBuf.putFloat( 1.0f).putFloat(-1.0f).putFloat(0.0f).putFloat(1.0f);
            vBuf.putFloat( 1.0f).putFloat( 1.0f).putFloat(0.0f).putFloat(1.0f);
            // v2: top-right
            vBuf.putFloat( 1.0f).putFloat( 1.0f).putFloat(0.0f).putFloat(1.0f);
            vBuf.putFloat( 1.0f).putFloat( 0.0f).putFloat(0.0f).putFloat(1.0f);
            // v3: top-left
            vBuf.putFloat(-1.0f).putFloat( 1.0f).putFloat(0.0f).putFloat(1.0f);
            vBuf.putFloat( 0.0f).putFloat( 0.0f).putFloat(0.0f).putFloat(1.0f);
            vBuf.flip();

            vertexBuffer = new VertexBuffer(128, MemoryTypes.HOST_MEM);
            vertexBuffer.copyBuffer(vBuf, 128);

            // 6 indices * 2 bytes = 12 bytes
            ByteBuffer iBuf = stack.malloc(12);
            iBuf.putShort((short) 0);
            iBuf.putShort((short) 1);
            iBuf.putShort((short) 2);
            iBuf.putShort((short) 2);
            iBuf.putShort((short) 3);
            iBuf.putShort((short) 0);
            iBuf.flip();

            indexBuffer = new IndexBuffer(12, MemoryTypes.HOST_MEM, IndexBuffer.IndexType.UINT16);
            indexBuffer.copyBuffer(iBuf, 12);
        }
    }

    public static void renderQuad(VkCommandBuffer cmd) {
        if (vertexBuffer == null) init();

        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffer.getId()), stack.longs(0L));
            vkCmdBindIndexBuffer(cmd, indexBuffer.getId(), 0L, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexed(cmd, 6, 1, 0, 0, 0);
        }
    }

    public static synchronized void cleanUp() {
        if (vertexBuffer != null) {
            vertexBuffer.scheduleFree();
            vertexBuffer = null;
        }
        if (indexBuffer != null) {
            indexBuffer.scheduleFree();
            indexBuffer = null;
        }
    }

    private QuadRenderer() {}
}
