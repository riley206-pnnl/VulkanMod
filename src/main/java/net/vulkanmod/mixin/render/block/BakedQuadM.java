package net.vulkanmod.mixin.render.block;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import net.vulkanmod.render.chunk.build.frapi.helper.NormalHelper;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.model.quad.ModelQuadView;
import net.vulkanmod.render.model.quad.ModelQuadFlags;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.vulkanmod.render.model.quad.ModelQuad.VERTEX_SIZE;

@Mixin(BakedQuad.class)
public abstract class BakedQuadM implements ModelQuadView {

    @Shadow @Final private Direction direction;
    @Shadow @Final private BakedQuad.MaterialInfo materialInfo;

    @Shadow
    public abstract Vector3fc position(int i);

    @Shadow
    public abstract long packedUV(int i);

    private int flags;
    private int normal;
    private QuadFacing facing;

    @Inject(method = "<init>(Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;JJJJLnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;Lnet/neoforged/neoforge/client/model/quad/BakedNormals;Lnet/neoforged/neoforge/client/model/quad/BakedColors;)V", at = @At("RETURN"))
    private void onInit(Vector3fc position0, Vector3fc position1,
                        Vector3fc position2, Vector3fc position3,
                        long packedUV0, long packedUV1,
                        long packedUV2, long packedUV3,
                        Direction direction,
                        BakedQuad.MaterialInfo materialInfo,
                        BakedNormals bakedNormals,
                        BakedColors bakedColors,
                        CallbackInfo ci) {
        initQuad(direction, bakedNormals);
    }

    @Inject(method = "<init>(Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;JJJJLnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;)V", at = @At("RETURN"))
    private void onInit10(Vector3fc position0, Vector3fc position1,
                          Vector3fc position2, Vector3fc position3,
                          long packedUV0, long packedUV1,
                          long packedUV2, long packedUV3,
                          Direction direction,
                          BakedQuad.MaterialInfo materialInfo,
                          CallbackInfo ci) {
        if (this.normal == 0) {
            initQuad(direction, null);
        }
    }

    private void initQuad(Direction direction, BakedNormals bakedNormals) {
        this.flags = ModelQuadFlags.getQuadFlags(this, direction);

        int packedNormal = 0;
        if (bakedNormals != null && !BakedNormals.isUnspecified(bakedNormals.normal(0))) {
            packedNormal = bakedNormals.normal(0);
        } else {
            packedNormal = NormalHelper.computePackedNormal(this);
        }

        if (packedNormal == 0 && direction != null) {
            packedNormal = NormalHelper.packedNormalFromDirection(direction);
        }

        this.normal = packedNormal;
        this.facing = QuadFacing.fromNormal(packedNormal);

        if (this.facing == null || this.facing == QuadFacing.UNDEFINED) {
            this.facing = direction != null ? QuadFacing.fromDirection(direction) : QuadFacing.UNDEFINED;
        }
    }

    @Override
    public int getFlags() {
        return flags;
    }

    @Override
    public float getX(int idx) {
        return this.position(idx).x();
    }

    @Override
    public float getY(int idx) {
        return this.position(idx).y();
    }

    @Override
    public float getZ(int idx) {
        return this.position(idx).z();
    }

    @Override
    public int getColor(int idx) {
        return 0xFFFFFFFF;
    }

    @Override
    public float getU(int idx) {
        return UVPair.unpackU(this.packedUV(idx));
    }

    @Override
    public float getV(int idx) {
        return UVPair.unpackV(this.packedUV(idx));
    }

    @Override
    public int getColorIndex() {
        return this.materialInfo.tintIndex();
    }

    @Override
    public Direction lightFace() {
        return this.direction;
    }

    @Override
    public Direction getFacingDirection() {
        return this.direction;
    }

    @Override
    public QuadFacing getQuadFacing() {
        return this.facing;
    }

    @Override
    public int getNormal() {
        return this.normal;
    }

    @Override
    public boolean isTinted() {
        return this.materialInfo.isTinted();
    }

    private static int vertexOffset(int vertexIndex) {
        return vertexIndex * VERTEX_SIZE;
    }
}
