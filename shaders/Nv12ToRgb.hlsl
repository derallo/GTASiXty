// Converts NV12 (R8_UNORM Y + R8G8_UNORM interleaved UV, BT.709 video range)
// into B8G8R8A8_UNORM (or R8G8B8A8_UNORM) linear/sRGB display color on D3D12.

Texture2D<float> yPlane : register(t0);
Texture2D<float2> uvPlane : register(t1);
RWTexture2D<unorm float4> outColor : register(u0);

cbuffer Constants : register(b0) {
    uint width;
    uint height;
    uint _pad0;
    uint _pad1;
};

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= width || tid.y >= height) return;

    const float ySample = yPlane[tid.xy];
    // Bilinear chroma upsampling for 4:2:0 NV12
    const float2 uvCoord = (float2(tid.xy) + 0.5) * 0.5 - 0.5;
    const int2 uvSize = int2((width + 1) / 2, (height + 1) / 2);
    const int2 p0 = clamp(int2(floor(uvCoord)), int2(0, 0), uvSize - 1);
    const int2 p1 = min(p0 + int2(1, 0), uvSize - 1);
    const int2 p2 = min(p0 + int2(0, 1), uvSize - 1);
    const int2 p3 = min(p0 + int2(1, 1), uvSize - 1);
    const float2 f = frac(max(uvCoord, 0.0));

    const float2 uv = lerp(
        lerp(uvPlane[p0], uvPlane[p1], f.x),
        lerp(uvPlane[p2], uvPlane[p3], f.x),
        f.y
    );

    // BT.709 limited-range (Y: 16..235, UV: 16..240) to full-range RGB
    const float Y = (ySample - (16.0 / 255.0)) * (255.0 / 219.0);
    const float Cb = (uv.x - (128.0 / 255.0)) * (255.0 / 224.0);
    const float Cr = (uv.y - (128.0 / 255.0)) * (255.0 / 224.0);

    const float r = Y + 1.5748 * Cr;
    const float g = Y - 0.187324 * Cb - 0.468124 * Cr;
    const float b = Y + 1.8556 * Cb;

    outColor[tid.xy] = float4(saturate(float3(r, g, b)), 1.0);
}
