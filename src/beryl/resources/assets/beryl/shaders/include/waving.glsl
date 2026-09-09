void applyWaving(inout vec4 position, inout vec3 normal, float lightY) {
    // Shared world-space phases keep adjacent water sections continuous.
    float exposure = smoothstep(0.65, 0.95, lightY);
    float fade = 1.0 - smoothstep(160.0, 256.0, length(position.xz));
    float amplitude = 0.035 * exposure * fade;
    vec2 world = position.xz + ViewPos.xz;
    vec2 k0 = vec2(0.4, 0.15);
    vec2 k1 = vec2(-0.24, 0.33);
    float phase0 = 2000.0 * GameTime + dot(k0, world);
    float phase1 = 1500.0 * GameTime + dot(k1, world);
    position.y += amplitude * (sin(phase0) + 0.6 * sin(phase1));
    // Surface normals are the negative height gradient. Preserve vertical water faces.
    vec2 slope = amplitude * (k0 * cos(phase0) + 0.6 * k1 * cos(phase1));
    normal = normalize(normal + vec3(-slope.x, 0.0, -slope.y) * max(normal.y, 0.0));
}
