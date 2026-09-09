vec3 normalVS = normalize(Normal);
vec3 vCamToSampleInVS = normalize(fragPos);
vec3 viewDir = -vCamToSampleInVS;
vec4 vReflectionInVS = vec4(reflect(vCamToSampleInVS, normalVS.xyz), 0.0);

// Sky reflection
float RoUp = dot(vReflectionInVS.xyz, UpVector);
RoUp = max(RoUp, 0.0);

//TODO precompute
float SoUp = max(dot(UpVector, LightDir), 0.0);
float RdotL =  max(dot(vReflectionInVS.xyz, LightDir), 0.0);
vec3 skyColor = getSkyColor(RdotL, RoUp, SoUp).rgb;
skyColor = mix(skyColor, max(skyColor, SkyColor.rgb * 1.5 + vec3(0.02, 0.035, 0.07)), NightFactor);

vec3 reflectedColor = skyColor;

#ifdef SSR
    reflectedColor = ssr(fragPos, vReflectionInVS, skyColor);
#endif

float roughness = 0.03;
float metallic = 0.0;
float ao = 1.0;
vec3 albedo = texColor.rgb * 0.3;

vec3 N = normalize(Normal);
vec3 V = viewDir;
vec3 H1 = normalize(V + vReflectionInVS.xyz);

float bias = (1.0 - max(dot(N, LightDir), 0.0)) * 0.001;
float shadow = ShadowCalculation(ShadowMap, posLightSpace, ShadowTexelSize, bias);

vec3 F0 = vec3(0.04);

vec3 radiance = reflectedColor.rgb;

vec3 color1 = LightingGGX2(viewDir, N, vReflectionInVS.xyz, N, albedo, reflectedColor.rgb, F0, roughness, metallic);

// Sun / Moon Lighting
bool isNight = NightFactor > 0.4;
vec3 L = normalize(mix(LightDir, -LightDir, float(isNight)));
vec3 lightCol = mix(LightColor * 0.6, vec3(0.25, 0.35, 0.5) * LightIntensity, float(isNight));
radiance = lightCol * LightVisibility;

vec3 R = vReflectionInVS.xyz;

//    vec3 centerToRay = dot(L, R) * (R - L);
vec3 centerToRay = (R - L);
float radius = 0.042;
// closest point
float d = length(centerToRay);
vec3 O = centerToRay * saturate(radius / max(d, 0.0001));
L = L + O;
L = normalize(L);

vec3 H = normalize(V + L);

float NdotL = max(dot(N, L), 0.0);
float NdotV = max(dot(N, V), 0.0);
vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

//vec3 specular = LightingSpecularGGX(viewDir, N, L, H, NdotL, NdotV, radiance * 0.4, F, roughness, metallic);
float NdotH  = max(dot(N, H), 0.0);
vec3 specular = NdotL * NdotV * F / (1.01 - (NdotH * NdotH)) * radiance * 0.2 * length(texColor.rgb);

vec3 kD = vec3(1.0) - F;
vec3 diffuse = (kD * albedo * 0.31830988618) * radiance * NdotL;

float effectiveShadow = mix(shadow, 1.0, clamp((NightFactor - 0.3) * 2.0, 0.0, 1.0));
vec3 color2 = (diffuse + specular) * effectiveShadow;

NdotV = abs(dot(N, V));
float fresnel = pow(1.0 - NdotV, 3.0);
float baseAlpha = mix(max(texColor.a * 0.65, 0.55), max(texColor.a * 0.75, 0.65), NightFactor);
float alpha = clamp(mix(baseAlpha, 0.95, fresnel), 0.0, 1.0);

color.a = alpha;

// Sky access modulation: full reflection when open to sky, fades out in deep caves
float skyAccess = mix(light.z, clamp(light.y * 3.0, 0.0, 1.0), NightFactor);
color.rgb = color1 * skyAccess + color2 * skyAccess;

// Water body ambient lighting: preserves clear water color and presence at night
float ambientFloor = mix(light.y, max(light.y, 0.16), NightFactor);
vec3 ambientLightEffective = mix(AmbientLight, AmbientLight * 1.5 + vec3(0.1, 0.15, 0.25), NightFactor);
color.rgb += texColor.rgb * 0.65 * (ambientFloor * ambientLightEffective + light.x * vec3(1.0, 0.7, 0.5));

// Depth-based absorption (Beer-Lambert)
float waterDepth = max(vWaterDepth, 0.0);
float depthFactor = 1.0 - exp(-WaterAbsorption * waterDepth);
vec3 deepWaterColor = vec3(0.02, 0.075, 0.17) * (0.25 + 0.75 * skyAccess) * max(LightVisibility, 0.2);
color.rgb = mix(color.rgb, deepWaterColor, depthFactor);
color.a = max(color.a, depthFactor);

//color.rgb = normal.rgb;
//color.a = 1.0f;