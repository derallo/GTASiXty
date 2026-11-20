// Extracts full-range BT.709 luma (R8_UNORM) from BGRA/RGBA texture.

Texture2D<float4> colorTex : register(t0);
RWTexture2D<unorm float> lumaTex : register(u0);

cbuffer Constants : register(b0) {
    uint width;
    uint height;
    uint _pad0;
    uint _pad1;
};

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= width || tid.y >= height) return;
    const float3 rgb = colorTex[tid.xy].rgb;
    lumaTex[tid.xy] = dot(rgb, float3(0.2126, 0.7152, 0.0722));
}
