// Frame-generation motion refinement (ported from Veyra FlowRefine.hlsl).
// Picks the best 3x3 neighboring grid cell vector per flow pixel using 3x3 luma matching,
// and measures forward-backward consistency against reverseFlow (S10.5).

Texture2D<int2> rawFlow : register(t0);         // grid extent, S10.5, current -> previous
Texture2D<float4> previousColor : register(t1); // flow/working extent
Texture2D<float4> currentColor : register(t2);  // flow/working extent
Texture2D<int2> reverseFlow : register(t3);     // grid extent, S10.5, previous -> current
RWTexture2D<float4> refined : register(u0);     // xy vector (in color pixels), z best cost, w fb disagreement

cbuffer FlowRefineConstants : register(b0) {
    uint gridW;
    uint gridH;
    uint flowW;
    uint flowH;
    uint colorW;
    uint colorH;
    uint gridSize;
    float tieBias;
};

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

[numthreads(16, 16, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= flowW || id.y >= flowH) return;

    const float2 flowToColor = float2(colorW, colorH) / float2(flowW, flowH);
    const int2 colorLimit = int2(colorW - 1, colorH - 1);
    const int2 pColor = clamp(int2((float2(id.xy) + 0.5) * flowToColor), int2(0, 0), colorLimit);

    float current[9];
    [unroll]
    for (int j = 0; j < 9; ++j) {
        current[j] = luma(currentColor[clamp(pColor + int2(j % 3 - 1, j / 3 - 1), int2(0, 0), colorLimit)].rgb);
    }

    const int2 cell = clamp(pColor / int(gridSize), int2(0, 0), int2(gridW - 1, gridH - 1));
    const int2 cellLimit = int2(gridW - 1, gridH - 1);

    float bestCost = 1e9;
    float2 best = float2(0.0, 0.0);

    [unroll]
    for (int k = 0; k < 9; ++k) {
        const int2 offset = int2(k % 3 - 1, k / 3 - 1);
        const float2 v = float2(rawFlow[clamp(cell + offset, int2(0, 0), cellLimit)]) * (1.0 / 32.0);
        const int2 q = pColor + int2(round(v));
        float cost = 0.0;
        [unroll]
        for (int j = 0; j < 9; ++j) {
            cost += abs(current[j] - luma(previousColor[clamp(q + int2(j % 3 - 1, j / 3 - 1), int2(0, 0), colorLimit)].rgb));
        }
        cost /= 9.0;
        if (k != 4) cost += tieBias;
        if (cost < bestCost) {
            bestCost = cost;
            best = v;
        }
    }

    const int2 backCell = clamp(int2(floor((float2(pColor) + 0.5 + best) / float(gridSize))), int2(0, 0), cellLimit);
    const float disagreement = length(best + float2(reverseFlow[backCell]) * (1.0 / 32.0));

    refined[id.xy] = float4(best, bestCost, disagreement);
}
