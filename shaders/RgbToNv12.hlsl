// Converts RGBA/BGRA output texture into BT.709 limited-range NV12 planes (R8_UNORM Y + R8G8_UNORM UV).

Texture2D<float4> rgbInput : register(t0);
RWTexture2D<unorm float> yPlane : register(u0);
RWTexture2D<unorm float2> uvPlane : register(u1);

cbuffer Constants : register(b0) {
    uint width;
    uint height;
    uint _pad0;
    uint _pad1;
};

float3 rgbToYuv709(float3 c) {
    c = saturate(c);
    const float y = dot(c, float3(0.2126, 0.7152, 0.0722));
    return float3(
        (16.0 + 219.0 * y) / 255.0,
        (128.0 + 224.0 * (c.b - y) / 1.8556) / 255.0,
        (128.0 + 224.0 * (c.r - y) / 1.5748) / 255.0
    );
}

[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= width || tid.y >= height) return;

    const float3 yuv = rgbToYuv709(rgbInput[tid.xy].rgb);
    yPlane[tid.xy] = yuv.x;

    if ((tid.x & 1u) == 0u && (tid.y & 1u) == 0u) {
        const uint2 p0 = tid.xy;
        const uint2 p1 = min(tid.xy + uint2(1, 0), uint2(width - 1, height - 1));
        const uint2 p2 = min(tid.xy + uint2(0, 1), uint2(width - 1, height - 1));
        const uint2 p3 = min(tid.xy + uint2(1, 1), uint2(width - 1, height - 1));
        const float3 avgRgb = (rgbInput[p0].rgb + rgbInput[p1].rgb + rgbInput[p2].rgb + rgbInput[p3].rgb) * 0.25;
        uvPlane[tid.xy / 2] = rgbToYuv709(avgRgb).yz;
    }
}
