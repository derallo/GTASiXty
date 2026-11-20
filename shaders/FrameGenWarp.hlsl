// Explained-Warp Bidirectional Frame Generation with HUD Protection.
// Synthesizes intermediate frame at phase t in (0, 1) between prevColor (t=0) and currColor (t=1).
// Implements multi-trajectory candidate lookup + Transfusion/Veyra Explained-Warp policy:
//   Es = L1(prev, curr)
//   Em = L1(warpedPrev, warpedCurr)
//   Explained when Em + 0.08 < Es and Em < 0.15, with zero-motion static UI & HUD mask protection.

Texture2D<float4> prevColor : register(t0);
Texture2D<float4> currColor : register(t1);
Texture2D<float2> fwdMotion : register(t2);    // current -> previous (in color pixels)
Texture2D<int2>   revMotionRaw : register(t3); // previous -> current (S10.5 at grid resolution)
Texture2D<float4> refineMeta : register(t4);   // z=cost, w=disagreement
RWTexture2D<unorm float4> outColor : register(u0);

cbuffer FrameGenConstants : register(b0) {
    uint width;
    uint height;
    uint flowW;
    uint flowH;
    uint gridW;
    uint gridH;
    uint gridSize;
    float phase; // 0.5 for 30->60 FPS, 0.3333 / 0.6667 for 30->90 FPS

    // HUD protection rects in normalized [0..1] UV: (xMin, yMin, xMax, yMax)
    float4 hudRect0; // e.g. bottom-left minimap
    float4 hudRect1; // e.g. top-right status/weapon wheel
    uint hudProtectEnabled;
    float staticUiThreshold;
    float _pad0;
    float _pad1;
};

float3 sampleBilinear(Texture2D<float4> tex, float2 coord) {
    const float2 clamped = clamp(coord, float2(0.0, 0.0), float2(width - 1, height - 1));
    const int2 p0 = int2(floor(clamped));
    const int2 p1 = min(p0 + int2(1, 0), int2(width - 1, height - 1));
    const int2 p2 = min(p0 + int2(0, 1), int2(width - 1, height - 1));
    const int2 p3 = min(p0 + int2(1, 1), int2(width - 1, height - 1));
    const float2 f = frac(clamped);
    return lerp(
        lerp(tex[p0].rgb, tex[p1].rgb, f.x),
        lerp(tex[p2].rgb, tex[p3].rgb, f.x),
        f.y
    );
}

float2 sampleFwdMotion(float2 colorCoord) {
    const float2 fCoord = clamp(colorCoord * float2(flowW, flowH) / float2(width, height) - 0.5,
                                float2(0.0, 0.0), float2(flowW - 1, flowH - 1));
    const int2 p0 = int2(floor(fCoord));
    const int2 p1 = min(p0 + int2(1, 0), int2(flowW - 1, flowH - 1));
    const int2 p2 = min(p0 + int2(0, 1), int2(flowW - 1, flowH - 1));
    const int2 p3 = min(p0 + int2(1, 1), int2(flowW - 1, flowH - 1));
    const float2 f = frac(fCoord);
    return lerp(
        lerp(fwdMotion[p0], fwdMotion[p1], f.x),
        lerp(fwdMotion[p2], fwdMotion[p3], f.x),
        f.y
    );
}

float2 sampleRevAsFwdMotion(float2 colorCoord) {
    const int2 cell = clamp(int2(colorCoord / float(gridSize)), int2(0, 0), int2(gridW - 1, gridH - 1));
    return -float2(revMotionRaw[cell]) * (1.0 / 32.0);
}

bool insideHudRect(float2 uv, float4 rect) {
    return uv.x >= rect.x && uv.y >= rect.y && uv.x <= rect.z && uv.y <= rect.w;
}

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= width || tid.y >= height) return;

    const float2 p = float2(tid.xy);
    const float2 uv = (p + 0.5) / float2(width, height);

    const float3 unwarped0 = prevColor[tid.xy].rgb;
    const float3 unwarped1 = currColor[tid.xy].rgb;
    const float es = dot(abs(unwarped0 - unwarped1), float3(1.0, 1.0, 1.0));

    // HUD Protection: corner minimap/status zones or completely static high-contrast UI
    if (hudProtectEnabled != 0) {
        if (insideHudRect(uv, hudRect0) || insideHudRect(uv, hudRect1)) {
            outColor[tid.xy] = float4(lerp(unwarped0, unwarped1, phase), 1.0);
            return;
        }
    }

    if (es < staticUiThreshold) {
        outColor[tid.xy] = float4(lerp(unwarped0, unwarped1, phase), 1.0);
        return;
    }

    // Evaluate multi-trajectory motion candidates so fast moving objects never tear at leading/trailing edges:
    //   v0: local current->previous vector at p
    //   v1: negated previous->current vector at p
    //   v2: trajectory-pulled current->previous vector near curr position
    //   v3: trajectory-pulled negated previous->current vector near prev position
    const float2 v0 = sampleFwdMotion(p);
    const float2 v1 = sampleRevAsFwdMotion(p);
    const float2 v2 = sampleFwdMotion(p - (1.0 - phase) * v0);
    const float2 v3 = sampleRevAsFwdMotion(p + phase * v1);

    const float2 cands[4] = { v0, v1, v2, v3 };
    float bestEm = 1e9;
    float2 bestV = v0;
    float3 bestW0 = unwarped0;
    float3 bestW1 = unwarped1;

    [unroll]
    for (int i = 0; i < 4; ++i) {
        const float2 v = cands[i];
        const float2 pos0 = p + phase * v;
        const float2 pos1 = p - (1.0 - phase) * v;
        const bool inBounds = all(pos0 >= 0.0) && all(pos0 <= float2(width - 1, height - 1)) &&
                              all(pos1 >= 0.0) && all(pos1 <= float2(width - 1, height - 1));
        const float3 w0 = sampleBilinear(prevColor, pos0);
        const float3 w1 = sampleBilinear(currColor, pos1);
        const float em = dot(abs(w0 - w1), float3(1.0, 1.0, 1.0)) + (inBounds ? 0.0 : 0.25);
        if (em < bestEm) {
            bestEm = em;
            bestV = v;
            bestW0 = w0;
            bestW1 = w1;
        }
    }

    // Explained-Warp Policy:
    // If the warp genuinely explains the pixel change (Em + 0.08 < Es and Em < 0.25),
    // interpolate cleanly along the motion trajectory. Otherwise fall back to disocclusion-safe
    // nearest frame / confidence-weighted blend to prevent ghosting.
    float3 result;
    const bool explained = (bestEm + 0.08 < es) && (bestEm < 0.25);
    if (explained) {
        result = lerp(bestW0, bestW1, phase);
    } else {
        const int2 fIdx = clamp(int2((p + 0.5) * float2(flowW, flowH) / float2(width, height)),
                                int2(0, 0), int2(flowW - 1, flowH - 1));
        const float4 meta = refineMeta[fIdx];
        const float motionConf = saturate(1.0 - meta.w * 0.15) * saturate(1.0 - meta.z * 2.0);
        const float3 fallback = phase < 0.5 ? unwarped0 : unwarped1;
        const float3 warpedBlend = lerp(bestW0, bestW1, phase);
        result = lerp(fallback, warpedBlend, motionConf * 0.5);
    }

    outColor[tid.xy] = float4(saturate(result), 1.0);
}
