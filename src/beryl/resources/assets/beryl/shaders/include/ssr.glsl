// View-space ray tracing against the opaque HDR/depth snapshot. Vulkan depth is [0, 1].
float computeDepthVS(float depth) {
    return -ProjMat[3][2] / (depth + ProjMat[2][2]);
}

bool projectReflection(vec3 position, out vec3 screen) {
    vec4 clip = ProjMat * vec4(position, 1.0);
    if (clip.w <= 0.0) return false;
    screen = clip.xyz / clip.w;
    screen.xy = screen.xy * vec2(0.5, -0.5) + 0.5;
    return all(greaterThan(screen, vec3(0.0))) && all(lessThan(screen, vec3(1.0)));
}

vec3 ssr(vec3 origin, vec4 reflection, vec3 skyColor) {
    vec3 direction = normalize(reflection.xyz);
    float limit = 100.0;
    // Clip toward-camera rays at the near plane instead of dropping the entire reflection.
    if (direction.z > 0.0) limit = min(limit, (-0.06 - origin.z) / direction.z);
    if (limit <= 0.1) return skyColor;

    float previous = 0.08;
    float distance = previous;
    float stepLength = 0.15;
    for (int i = 0; i < 48; i++) {
        distance = min(distance + stepLength, limit);
        vec3 position = origin + direction * distance;
        vec3 screen;
        if (!projectReflection(position, screen)) break;
        float depth = texture(Depthbuffer, screen.xy).r;
        float delta = computeDepthVS(depth) - position.z;
        if (depth < 0.999999 && delta > 0.0) {
            float lo = previous;
            float hi = distance;
            for (int refinement = 0; refinement < 6; refinement++) {
                float mid = (lo + hi) * 0.5;
                vec3 midPosition = origin + direction * mid;
                vec3 midScreen;
                if (!projectReflection(midPosition, midScreen)) return skyColor;
                float midDepth = texture(Depthbuffer, midScreen.xy).r;
                if (computeDepthVS(midDepth) - midPosition.z > 0.0) hi = mid;
                else lo = mid;
            }
            position = origin + direction * hi;
            if (!projectReflection(position, screen)) return skyColor;
            depth = texture(Depthbuffer, screen.xy).r;
            delta = computeDepthVS(depth) - position.z;
            float thickness = clamp(-position.z * 0.01, 0.15, 0.8);
            if (depth < 0.999999 && delta >= 0.0 && delta < thickness) {
                vec2 edge = min(screen.xy, 1.0 - screen.xy);
                float confidence = smoothstep(0.0, 0.06, min(edge.x, edge.y));
                confidence *= 1.0 - smoothstep(75.0, 100.0, hi);
                return mix(skyColor, texture(Framebuffer, screen.xy).rgb, confidence);
            }
            return skyColor;
        }
        if (distance >= limit) break;
        previous = distance;
        stepLength *= 1.1;
    }
    return skyColor;
}
