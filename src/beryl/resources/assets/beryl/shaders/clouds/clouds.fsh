#version 450

#include "fog.glsl"

layout(binding = 1) uniform UBO {
    vec4 ColorModulator;
    vec3 LightDir;
    vec3 LightColor;
    vec4 SkyColor;
    vec4 FogColor;
    vec3 UpVector;
    float FogStart;
    float FogEnd;
    float FogCloudsEnd;
    float LightVisibility;
    float NightFactor;
};

vec4 getMieScattering(const vec3 fragDir, float RoUp, const float SoUp) {
    vec4 color;

    float FdotL = max(dot(fragDir, LightDir), 0.0);

    // Mie scattering approx
    color.rgb = LightColor * min((1.0 / ((1 - FdotL) * 20.0 + 1.0)), 0.8);

    return color;
}

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec3 fragPos;
layout(location = 2) in vec3 normal;
layout(location = 3) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor;

    vec3 fragDir = normalize(fragPos);
    float RdotUp = max(dot(fragDir, UpVector), 0.0);

    float LdotUp = max(dot(UpVector, LightDir), 0.0);
    float NdotDown = max(dot(-UpVector, normal), 0.0);
    float NdotL = max(dot(normal, LightDir), 0.0);
    vec3 H = normalize(-fragDir + LightDir);
    float NdotH = max(dot(normal, H), 0.0);
    float FdotL = max(dot(fragDir, LightDir), 0.0);

    // Distance horizon fade (clouds stay completely solid until far distance)
    float fadeEnd = max(FogEnd * 1.5, 384.0);
    float fadeStart = fadeEnd * 0.75;
    float horizonFade = 1.0 - clamp((vertexDistance - fadeStart) / (fadeEnd - fadeStart), 0.0, 1.0);
    color.a = vertexColor.a * horizonFade;

    // Ambient lighting from sky and fog
    vec3 ambient = mix(FogColor.rgb, SkyColor.rgb, 0.4) * (0.45 + 0.35 * (1.0 - NdotDown));
    // Soft moonlight ambient at night
    ambient += vec3(0.05, 0.06, 0.09) * NightFactor * (0.6 + 0.4 * (1.0 - NdotDown));

    // Direct sun/moon illumination
    float directFactor = NdotL * 0.7 + NdotH * 0.3;
    vec3 directLight = LightColor * 0.14 * directFactor;

    // Forward scattering / silver lining when looking towards the sun
    vec3 mie = LightColor * (0.04 * pow(FdotL, 4.0) + 0.02 * min(1.0 / ((1.0 - FdotL) * 15.0 + 1.0), 1.0));

    vec3 lighting = ambient + directLight + (0.2 + 0.3 * (1.0 - NdotDown)) * mie;

    color.rgb = vertexColor.rgb * lighting;
    fragColor = color;
}
