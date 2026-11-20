// AMD FidelityFX Robust Contrast-Adaptive Sharpening (RCAS).
// Applies edge-aware sharpening without ringing on the upscaled output texture.

Texture2D<float4> inputColor : register(t0);
RWTexture2D<unorm float4> outColor : register(u0);

cbuffer RcasConstants : register(b0) {
    uint width;
    uint height;
    float sharpness; // 0.0 (sharpest) .. 2.0 (softest), default ~0.25
    float _pad0;
};

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= width || tid.y >= height) return;

    const int2 p = int2(tid.xy);
    const int2 lim = int2(width - 1, height - 1);

    const float3 b = inputColor[clamp(p + int2(0, -1), int2(0, 0), lim)].rgb;
    const float3 d = inputColor[clamp(p + int2(-1, 0), int2(0, 0), lim)].rgb;
    const float3 e = inputColor[p].rgb;
    const float3 f = inputColor[clamp(p + int2(1, 0), int2(0, 0), lim)].rgb;
    const float3 h = inputColor[clamp(p + int2(0, 1), int2(0, 0), lim)].rgb;

    const float bL = b.g + 0.5 * (b.r + b.b);
    const float dL = d.g + 0.5 * (d.r + d.b);
    const float eL = e.g + 0.5 * (e.r + e.b);
    const float fL = f.g + 0.5 * (f.r + f.b);
    const float hL = h.g + 0.5 * (h.r + h.b);

    const float nz = 0.25 * (bL + dL + fL + hL) - eL;
    const float range = max(max(bL, dL), max(fL, max(hL, eL))) - min(min(bL, dL), min(fL, min(hL, eL)));
    const float nzClamp = range > 1e-4 ? saturate(abs(nz) / range) : 0.0;

    const float3 mn4 = min(min(b, d), min(f, h));
    const float3 mx4 = max(max(b, d), max(f, h));
    const float3 mn = min(mn4, e);
    const float3 mx = max(mx4, e);

    const float3 hitMin = mn * rcp(max(4.0 * mx, 1e-5));
    const float3 hitMax = (1.0 - mx) * rcp(max(4.0 * mn - 4.0, -1e-5));
    const float3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0));
    lobe *= exp2(-sharpness);
    lobe *= (1.0 - 0.5 * nzClamp);

    const float3 pix = (lobe * (b + d + f + h) + e) * rcp(4.0 * lobe + 1.0);
    outColor[tid.xy] = float4(saturate(pix), 1.0);
}
