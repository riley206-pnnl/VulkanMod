package net.vulkanmod.shaders;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** CPU-side light-space state shared by the shadow pass and pack uniforms. */
public final class PackShadowMatrices {
    public record State(Matrix4f modelView, Matrix4f projection, Matrix4f renderProjection,
                        Matrix4f modelViewInverse, Matrix4f projectionInverse,
                        Vector3f sunDirection) {}

    /** Light-space bounds used by the runtime shadow diagnostic. The values
     * are post-divide NDC coordinates, so an area intersects the rasterized
     * shadow frustum when each axis overlaps [-1, 1]. */
    public record NdcBounds(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        public boolean intersectsUnitCube() {
            return maxX >= -1.0f && minX <= 1.0f
                    && maxY >= -1.0f && minY <= 1.0f
                    && maxZ >= -1.0f && minZ <= 1.0f;
        }
    }

    private PackShadowMatrices() {}

    /** Complementary's world0 sun orbit, kept in one place so the caster,
     * G-buffer lighting, and pack uniform aliases cannot drift apart. */
    public static Vector3f sunDirection(float sunAngle) {
        float tAmin = fract(sunAngle - 0.033333333f);
        float tAlin = tAmin < 0.433333333f
                ? tAmin * 1.15384615385f
                : tAmin * 0.882352941176f + 0.117647058824f;
        float hA = tAlin > 0.5f ? 1.0f : 0.0f;
        float tAfrc = fract(tAlin * 2.0f);
        float tAfrs = tAfrc * tAfrc * (3.0f - 2.0f * tAfrc);
        float tAmix = hA < 0.5f ? 0.3f : -0.1f;
        float timeAngle = (tAfrc * (1.0f - tAmix) + tAfrs * tAmix + hA) * 0.5f;
        float angle = fract(timeAngle - 0.25f) * (float) (Math.PI * 2.0);
        return new Vector3f((float) -Math.sin(angle), (float) Math.cos(angle), 0.0f).normalize();
    }

    /**
     * Builds a stable orthographic sun camera around the current player. The
     * camera is snapped in light space to one shadow texel to prevent
     * shimmering while the player moves by sub-block amounts.
     */
    public static State compute(float sunAngle, float cameraX, float cameraY, float cameraZ,
                                float shadowDistance, int resolution) {
        Vector3f sun = sunDirection(sunAngle);

        // Chunk vertices are submitted camera-relative by DrawBuffers.  The
        // light camera must therefore be centered in that same coordinate
        // space; using absolute camera coordinates here would translate the
        // world twice and make every shadow lookup miss the map.
        Vector3f center = new Vector3f(0.0f, 0.0f, 0.0f);
        float distance = Math.max(16.0f, shadowDistance);
        Vector3f eye = new Vector3f(center).fma(distance, sun);
        Vector3f up = Math.abs(sun.y) > 0.92f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
        Matrix4f modelView = new Matrix4f().lookAt(eye, center, up);

        float texelWorldSize = (distance * 2.0f) / Math.max(1, resolution);
        float lightX = modelView.m30();
        float lightY = modelView.m31();
        modelView.m30(Math.round(lightX / texelWorldSize) * texelWorldSize);
        modelView.m31(Math.round(lightY / texelWorldSize) * texelWorldSize);

        // Iris/OptiFine shader code expects the conventional OpenGL clip-Z
        // range and converts it to shadow texture coordinates itself. Vulkan
        // rasterization, however, requires a zero-to-one clip-Z projection.
        // Keep both forms: the pack-facing matrix is used by PlayerToShadow,
        // while the render form is used only for shadow-map rasterization.
        Matrix4f projection = new Matrix4f().ortho(-distance, distance,
                -distance, distance, 0.01f, distance * 4.0f, false);
        Matrix4f renderProjection = new Matrix4f().ortho(-distance, distance,
                -distance, distance, 0.01f, distance * 4.0f, true);
        return new State(modelView, projection, renderProjection,
                new Matrix4f(modelView).invert(),
                new Matrix4f(projection).invert(), sun);
    }

    /**
     * Transforms an axis-aligned camera-relative world box into the pack's
     * OpenGL light NDC space. This is intentionally independent of Vulkan
     * depth conversion: the shadow vertex wrapper performs that conversion
     * only after the pack's depth compression, while frustum membership in X,
     * Y and pre-compression Z is what we need to diagnose here.
     */
    public static NdcBounds transformBounds(State state,
                                            float minX, float minY, float minZ,
                                            float maxX, float maxY, float maxZ) {
        float outMinX = Float.POSITIVE_INFINITY;
        float outMinY = Float.POSITIVE_INFINITY;
        float outMinZ = Float.POSITIVE_INFINITY;
        float outMaxX = Float.NEGATIVE_INFINITY;
        float outMaxY = Float.NEGATIVE_INFINITY;
        float outMaxZ = Float.NEGATIVE_INFINITY;

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    Vector4f point = new Vector4f(
                            xi == 0 ? minX : maxX,
                            yi == 0 ? minY : maxY,
                            zi == 0 ? minZ : maxZ,
                            1.0f).mul(state.modelView()).mul(state.projection());
                    float invW = point.w == 0.0f ? 1.0f : 1.0f / point.w;
                    float x = point.x * invW;
                    float y = point.y * invW;
                    float z = point.z * invW;
                    outMinX = Math.min(outMinX, x);
                    outMinY = Math.min(outMinY, y);
                    outMinZ = Math.min(outMinZ, z);
                    outMaxX = Math.max(outMaxX, x);
                    outMaxY = Math.max(outMaxY, y);
                    outMaxZ = Math.max(outMaxZ, z);
                }
            }
        }
        return new NdcBounds(outMinX, outMinY, outMinZ, outMaxX, outMaxY, outMaxZ);
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }
}
