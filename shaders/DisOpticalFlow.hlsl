// Compute fallback bidirectional block optical flow (4x4 grid).
// Produces S10.5 flow vectors compatible with NVOFA output layout:
//   forwardFlow  = curr -> prev (R16G16_SINT)
//   backwardFlow = prev -> curr (R16G16_SINT)
//   costOut      = photometric SAD cost (R8_UINT)

Texture2D<float> prevLuma : register(t0);
Texture2D<float> currLuma : register(t1);
RWTexture2D<int2> forwardFlow : register(u0);
RWTexture2D<uint> forwardCost : register(u1);
RWTexture2D<int2> backwardFlow : register(u2);
RWTexture2D<uint> backwardCost : register(u3);

cbuffer Constants : register(b0) {
    uint width;
    uint height;
    uint gridW;
    uint gridH;
    uint gridSize;
    uint searchRadius;
    uint _pad0;
    uint _pad1;
};

float patchSad(Texture2D<float> srcTex, Texture2D<float> refTex, int2 centerSrc, int2 centerRef) {
    float sad = 0.0;
    const int2 lim = int2(width - 1, height - 1);
    [unroll]
    for (int dy = -2; dy <= 2; dy += 2) {
        [unroll]
        for (int dx = -2; dx <= 2; dx += 2) {
            const float a = srcTex[clamp(centerSrc + int2(dx, dy), int2(0, 0), lim)];
            const float b = refTex[clamp(centerRef + int2(dx, dy), int2(0, 0), lim)];
            sad += abs(a - b);
        }
    }
    return sad / 9.0;
}

void estimateDirection(Texture2D<float> srcTex, Texture2D<float> refTex, int2 center,
                       out int2 outVecS10_5, out uint outCost) {
    float bestSad = patchSad(srcTex, refTex, center, center);
    int2 bestOffset = int2(0, 0);

    // Coarse search (step 4)
    const int r = int(searchRadius);
    for (int dy0 = -r; dy0 <= r; dy0 += 4) {
        for (int dx0 = -r; dx0 <= r; dx0 += 4) {
            if (dx0 == 0 && dy0 == 0) continue;
            const float s = patchSad(srcTex, refTex, center, center + int2(dx0, dy0));
            if (s + 0.004 < bestSad) {
                bestSad = s;
                bestOffset = int2(dx0, dy0);
            }
        }
    }

    // Medium refinement (step 2)
    const int2 coarseCenter = bestOffset;
    for (int dy1 = -2; dy1 <= 2; dy1 += 2) {
        for (int dx1 = -2; dx1 <= 2; dx1 += 2) {
            const int2 off = coarseCenter + int2(dx1, dy1);
            const float s = patchSad(srcTex, refTex, center, center + off);
            if (s < bestSad) {
                bestSad = s;
                bestOffset = off;
            }
        }
    }

    // Fine 1-pixel refinement
    const int2 fineCenter = bestOffset;
    [unroll]
    for (int dy2 = -1; dy2 <= 1; ++dy2) {
        [unroll]
        for (int dx2 = -1; dx2 <= 1; ++dx2) {
            const int2 off = fineCenter + int2(dx2, dy2);
            const float s = patchSad(srcTex, refTex, center, center + off);
            if (s < bestSad) {
                bestSad = s;
                bestOffset = off;
            }
        }
    }

    outVecS10_5 = bestOffset * 32; // S10.5 fixed point
    outCost = uint(clamp(bestSad * 255.0, 0.0, 255.0));
}

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= gridW || tid.y >= gridH) return;
    const int2 center = int2(tid.xy * gridSize + gridSize / 2);

    int2 fwd = int2(0, 0);
    uint fwdC = 0;
    estimateDirection(currLuma, prevLuma, center, fwd, fwdC);
    forwardFlow[tid.xy] = fwd;
    forwardCost[tid.xy] = fwdC;

    int2 bwd = int2(0, 0);
    uint bwdC = 0;
    estimateDirection(prevLuma, currLuma, center, bwd, bwdC);
    backwardFlow[tid.xy] = bwd;
    backwardCost[tid.xy] = bwdC;
}
