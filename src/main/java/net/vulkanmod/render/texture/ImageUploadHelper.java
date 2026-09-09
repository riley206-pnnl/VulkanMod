package net.vulkanmod.render.texture;

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    public void submitCommands() {
        // Image uploads use the graphics queue itself. Queue order already
        // makes the upload visible to the following graphics submission, so
        // do not signal/recycle a binary semaphore for every atlas update.
        // Fence tracking is sufficient and avoids semaphore reuse hazards
        // during resource reloads and animated-texture updates.
        this.submitCommands(false);
    }

    public void submitCommands(boolean useSemaphore) {
        if (this.currentCmdBuffer == null) {
            return;
        }

        SpriteUpdateUtil.transitionLayouts();

        queue.submitCommands(this.currentCmdBuffer, useSemaphore);
        Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer, useSemaphore);

        this.currentCmdBuffer = null;
    }

    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        if (this.currentCmdBuffer == null) {
            this.currentCmdBuffer = this.queue.beginCommands();
        }

        return this.currentCmdBuffer;
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        return this.currentCmdBuffer;
    }
}
