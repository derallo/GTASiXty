// Disocclusion fill for frame-generation motion (after FlowRefine).
// Replaces unreliable/disoccluded motion vectors with nearby confident background motion
// sharing similar color, preventing background smearing ("jelly" artifacts).

Texture2D<float4> refined : register(t0);       // xy vector, z cost, w fb disagreement
Texture2D<float4> currentColor : register(t1);  // working color extent
RWTexture2D<float2> outputMotion : register(u0);// filled current->previous motion (color pixels)

cbuffer FlowFillConstants : register(b0) {
    uint flowW;
    uint flowH;
    uint colorW;
    uint colorH;
    float costLimit;
    float disagreementLimit;
    float distanceWeight;
    float _pad0;
};

static const int2 kDirs[8] = {
    int2(1, 0), int2(-1, 0), int2(0, 1), int2(0, -1),
    int2(1, 1), int2(-1, 1), int2(1, -1), int2(-1, -1)
};
static const int kRadii[3] = { 2, 6, 14 };

bool confident(float4 r) {
    return r.z <= costLimit && r.w <= disagreementLimit;
}

[numthreads(16, 16, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= flowW || id.y >= flowH) return;
    const int2 p = int2(id.xy);
    const float4 self = refined[p];
    if (confident(self)) {
        outputMotion[p] = self.xy;
        return;
    }

    const float2 flowToColor = float2(colorW, colorH) / float2(flowW, flowH);
    const int2 colorLimit = int2(colorW - 1, colorH - 1);
    const float3 selfColor = currentColor[clamp(int2((float2(p) + 0.5) * flowToColor), int2(0, 0), colorLimit)].rgb;

    float bestScore = 1e9;
    float2 bestVec = self.xy;

    [unroll]
    for (int r = 0; r < 3; ++r) {
        [unroll]
        for (int d = 0; d < 8; ++d) {
            const int2 q = p + kDirs[d] * kRadii[r];
            if (any(q < 0) || q.x >= int(flowW) || q.y >= int(flowH)) continue;
            const float4 cand = refined[q];
            if (!confident(cand)) continue;
            const float3 candColor = currentColor[clamp(int2((float2(q) + 0.5) * flowToColor), int2(0, 0), colorLimit)].rgb;
            const float score = dot(abs(candColor - selfColor), float3(1.0, 1.0, 1.0)) + distanceWeight * float(kRadii[r]);
            if (score < bestScore) {
                bestScore = score;
                bestVec = cand.xy;
            }
        }
    }

    outputMotion[p] = bestVec;
}
