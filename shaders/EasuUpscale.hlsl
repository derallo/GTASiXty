// AMD FidelityFX Super Resolution 1.0 (FSR1) Edge-Adaptive Spatial Upscaling (EASU).
// Scales inputColor (srcW x srcH) -> outColor (dstW x dstH) cleanly on D3D12 compute.

Texture2D<float4> inputColor : register(t0);
RWTexture2D<unorm float4> outColor : register(u0);

cbuffer EasuConstants : register(b0) {
    uint srcW;
    uint srcH;
    uint dstW;
    uint dstH;
};

float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }

void easuTap(inout float3 aC, inout float aW, float2 off, float2 dir, float2 len, float lob, float clp, float3 c) {
    float2 v;
    v.x = (off.x * dir.x) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = min(dot(v, v), clp);
    float wB = 0.4 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 1.5625 * wB - 0.5625;
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void easuSet(inout float2 dir, inout float len, float2 pp, bool biS, bool biT, bool biU, bool biV,
             float lA, float lB, float lC, float lD, float lE) {
    float w = 0.0;
    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
    if (biT) w = pp.x * (1.0 - pp.y);
    if (biU) w = (1.0 - pp.x) * pp.y;
    if (biV) w = pp.x * pp.y;

    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = lenX > 1e-5 ? rcp(lenX) : 0.0;
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = saturate(abs(dirX) * lenX);
    lenX *= lenX;
    len += lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = lenY > 1e-5 ? rcp(lenY) : 0.0;
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = saturate(abs(dirY) * lenY);
    lenY *= lenY;
    len += lenY * w;
}

float3 fetchSrc(int2 p) {
    return inputColor[clamp(p, int2(0, 0), int2(srcW - 1, srcH - 1))].rgb;
}

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= dstW || tid.y >= dstH) return;

    if (srcW == dstW && srcH == dstH) {
        outColor[tid.xy] = float4(inputColor[tid.xy].rgb, 1.0);
        return;
    }

    const float2 pp = (float2(tid.xy) + 0.5) * (float2(srcW, srcH) / float2(dstW, dstH)) - 0.5;
    const int2 fp = int2(floor(pp));
    const float2 f = pp - float2(fp);

    // 12-tap cross neighborhood around fp:
    //      b  c
    //   e  f  g  h
    //   i  j  k  l
    //      n  o
    const float3 b = fetchSrc(fp + int2(0, -1));
    const float3 c = fetchSrc(fp + int2(1, -1));
    const float3 e = fetchSrc(fp + int2(-1, 0));
    const float3 f0 = fetchSrc(fp + int2(0, 0));
    const float3 g = fetchSrc(fp + int2(1, 0));
    const float3 h = fetchSrc(fp + int2(2, 0));
    const float3 i = fetchSrc(fp + int2(-1, 1));
    const float3 j = fetchSrc(fp + int2(0, 1));
    const float3 k = fetchSrc(fp + int2(1, 1));
    const float3 l = fetchSrc(fp + int2(2, 1));
    const float3 n = fetchSrc(fp + int2(0, 2));
    const float3 o = fetchSrc(fp + int2(1, 2));

    const float lB = luma(b), lC = luma(c), lE = luma(e), lF = luma(f0);
    const float lG = luma(g), lH = luma(h), lI = luma(i), lJ = luma(j);
    const float lK = luma(k), lL = luma(l), lN = luma(n), lO = luma(o);

    float2 dir = float2(0.0, 0.0);
    float len = 0.0;
    easuSet(dir, len, f, true,  false, false, false, lB, lE, lF, lG, lJ);
    easuSet(dir, len, f, false, true,  false, false, lC, lF, lG, lH, lK);
    easuSet(dir, len, f, false, false, true,  false, lF, lI, lJ, lK, lN);
    easuSet(dir, len, f, false, false, false, true,  lG, lJ, lK, lL, lO);

    const float dir2 = dot(dir, dir);
    if (dir2 < 1e-6) {
        dir = float2(1.0, 0.0);
    } else {
        dir *= rsqrt(dir2);
    }

    len = len * 0.5;
    len *= len;
    const float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    const float2 len2 = float2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    const float lob = 0.5 + (0.25 - 0.5) * len;
    const float clp = rcp(lob);

    const float3 min4 = min(min(f0, g), min(j, k));
    const float3 max4 = max(max(f0, g), max(j, k));

    float3 aC = float3(0.0, 0.0, 0.0);
    float aW = 0.0;
    easuTap(aC, aW, float2( 0.0, -1.0) - f, dir, len2, lob, clp, b);
    easuTap(aC, aW, float2( 1.0, -1.0) - f, dir, len2, lob, clp, c);
    easuTap(aC, aW, float2(-1.0,  0.0) - f, dir, len2, lob, clp, e);
    easuTap(aC, aW, float2( 0.0,  0.0) - f, dir, len2, lob, clp, f0);
    easuTap(aC, aW, float2( 1.0,  0.0) - f, dir, len2, lob, clp, g);
    easuTap(aC, aW, float2( 2.0,  0.0) - f, dir, len2, lob, clp, h);
    easuTap(aC, aW, float2(-1.0,  1.0) - f, dir, len2, lob, clp, i);
    easuTap(aC, aW, float2( 0.0,  1.0) - f, dir, len2, lob, clp, j);
    easuTap(aC, aW, float2( 1.0,  1.0) - f, dir, len2, lob, clp, k);
    easuTap(aC, aW, float2( 2.0,  1.0) - f, dir, len2, lob, clp, l);
    easuTap(aC, aW, float2( 0.0,  2.0) - f, dir, len2, lob, clp, n);
    easuTap(aC, aW, float2( 1.0,  2.0) - f, dir, len2, lob, clp, o);

    const float3 pix = clamp(aC / max(aW, 1e-5), min4, max4);
    outColor[tid.xy] = float4(saturate(pix), 1.0);
}
