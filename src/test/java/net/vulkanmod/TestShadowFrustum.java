package net.vulkanmod;

import com.mojang.math.Axis;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import org.joml.Matrix4f;

public class TestShadowFrustum {
    @org.junit.Test public void testFrustum() {
        float celestialAngle = 0.0f;
        float sunPathRotation = 20.0f;

        Matrix4f lightView = new Matrix4f();
        lightView.identity();
        lightView.rotate(Axis.XP.rotationDegrees(90.0F));
        lightView.rotate(Axis.ZP.rotationDegrees(celestialAngle * -360.0F));
        lightView.rotate(Axis.XP.rotationDegrees(-sunPathRotation));

        double camX = 100.5;
        double camY = 70.0;
        double camZ = 200.5;

        double posTranslationX = camX - Math.floor(camX / 2.0) * 2.0;
        double posTranslationY = camY - Math.floor(camY / 2.0) * 2.0;
        double posTranslationZ = camZ - Math.floor(camZ / 2.0) * 2.0;

        Matrix4f view = new Matrix4f(lightView);
        view.translate((float)posTranslationX, (float)posTranslationY, (float)posTranslationZ);

        float orthoMatrixHalfLength = 24.0f * 16.0f;
        Matrix4f lightProjection = new Matrix4f();
        lightProjection.setOrthoSymmetric(orthoMatrixHalfLength * 2.0F, orthoMatrixHalfLength * 2.0F, -100.0F, 192.0F, true);

        VFrustum vFrustum = new VFrustum();
        vFrustum.calculateFrustum(view, lightProjection);
        vFrustum.setCamOffset(camX, camY, camZ);

        // Test chunk around player: (96, 64, 192) to (112, 80, 208)
        boolean inFrustum = vFrustum.testFrustum(96, 64, 192, 112, 80, 208);
        System.out.println("CHUNK (96, 64, 192) in shadow frustum: " + inFrustum);

        Matrix4f mvp = new Matrix4f(lightProjection).mul(view);
        org.joml.FrustumIntersection fiFalse = new org.joml.FrustumIntersection();
        org.joml.FrustumIntersection fiTrue = new org.joml.FrustumIntersection();
        fiFalse.set(mvp, false);
        fiTrue.set(mvp, true);

        // Test light space clip position for a block near player
        org.joml.Vector4f blockPos = new org.joml.Vector4f((float)(96 - camX), (float)(64 - camY), (float)(192 - camZ), 1.0f);
        org.joml.Vector4f clipPos = new org.joml.Vector4f();
        mvp.transform(blockPos, clipPos);
        System.out.println("Block clipPos before div: " + clipPos);
        System.out.println("Block clipPos.z / clipPos.w: " + (clipPos.z / clipPos.w));

        // Test block on ground (y = 70) and block on top of tree (y = 78)
        org.joml.Vector4f groundPos = new org.joml.Vector4f(0.0f, 0.0f, 0.0f, 1.0f); // at player
        org.joml.Vector4f treePos = new org.joml.Vector4f(0.0f, 8.0f, 0.0f, 1.0f); // 8 blocks above
        org.joml.Vector4f groundClip = new org.joml.Vector4f();
        org.joml.Vector4f treeClip = new org.joml.Vector4f();
        mvp.transform(groundPos, groundClip);
        mvp.transform(treePos, treeClip);
        System.out.println("Ground clipPos: " + groundClip + ", z/w: " + (groundClip.z / groundClip.w));
        System.out.println("Tree clipPos: " + treeClip + ", z/w: " + (treeClip.z / treeClip.w));

        float f = (float)(96 - camX);
        float f1 = (float)(64 - camY);
        float f2 = (float)(192 - camZ);
        float f3 = (float)(112 - camX);
        float f4 = (float)(80 - camY);
        float f5 = (float)(208 - camZ);

        System.out.println("fiFalse.testAab: " + fiFalse.testAab(f, f1, f2, f3, f4, f5));
        System.out.println("fiTrue.testAab: " + fiTrue.testAab(f, f1, f2, f3, f4, f5));

        // Test grid of chunks around player
        int inFrustumCount = 0;
        int totalTested = 0;
        for (int cx = -12; cx <= 12; cx++) {
            for (int cz = -12; cz <= 12; cz++) {
                for (int cy = -4; cy <= 16; cy++) {
                    totalTested++;
                    int x = ((int)Math.floor(camX / 16.0) + cx) * 16;
                    int y = cy * 16;
                    int z = ((int)Math.floor(camZ / 16.0) + cz) * 16;
                    if (vFrustum.testFrustum(x, y, z, x + 16, y + 16, z + 16)) {
                        inFrustumCount++;
                    }
                }
            }
        }
        System.out.println("Frustum test: " + inFrustumCount + " of " + totalTested + " chunks in frustum");

        // Test with different celestial angles
        for (float angle = 0.0f; angle <= 1.0f; angle += 0.1f) {
            Matrix4f lv = new Matrix4f();
            lv.identity();
            lv.rotate(Axis.XP.rotationDegrees(90.0F));
            lv.rotate(Axis.ZP.rotationDegrees(angle * -360.0F));
            lv.rotate(Axis.XP.rotationDegrees(-sunPathRotation));
            Matrix4f v = new Matrix4f(lv);
            v.translate((float)posTranslationX, (float)posTranslationY, (float)posTranslationZ);
            VFrustum vf = new VFrustum();
            vf.calculateFrustum(v, lightProjection);
            vf.setCamOffset(camX, camY, camZ);
            int count = 0;
            for (int cx = -12; cx <= 12; cx++) {
                for (int cz = -12; cz <= 12; cz++) {
                    for (int cy = -4; cy <= 16; cy++) {
                        int x = ((int)Math.floor(camX / 16.0) + cx) * 16;
                        int y = cy * 16;
                        int z = ((int)Math.floor(camZ / 16.0) + cz) * 16;
                        if (vf.testFrustum(x, y, z, x + 16, y + 16, z + 16)) {
                            count++;
                        }
                    }
                }
            }
            System.out.println("angle " + angle + ": " + count + " of " + totalTested + " chunks in frustum");
        }
    }
}
