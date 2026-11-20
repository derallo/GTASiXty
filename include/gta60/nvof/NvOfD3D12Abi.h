#pragma once
// NVIDIA Optical Flow SDK 5.0 D3D12 ABI declarations (MIT-compatible public SDK interface).

#include <d3d12.h>
#include <dxgi.h>
#include <cstdint>

#ifndef NVOFAPI
#define NVOFAPI __stdcall
#endif

#define NV_OF_API_MAJOR_VERSION 5
#define NV_OF_API_MINOR_VERSION 0
#define NV_OF_API_VERSION static_cast<uint16_t>((NV_OF_API_MAJOR_VERSION << 4) | NV_OF_API_MINOR_VERSION)

extern "C" {

typedef struct NvOFHandle_st* NvOFHandle;
typedef struct NvOFGPUBufferHandle_st* NvOFGPUBufferHandle;
typedef struct NVOFPrivDataHandle_st* NvOFPrivDataHandle;

typedef enum _NV_OF_STATUS {
    NV_OF_SUCCESS,
    NV_OF_ERR_OF_NOT_AVAILABLE,
    NV_OF_ERR_UNSUPPORTED_DEVICE,
    NV_OF_ERR_DEVICE_DOES_NOT_EXIST,
    NV_OF_ERR_INVALID_PTR,
    NV_OF_ERR_INVALID_PARAM,
    NV_OF_ERR_INVALID_CALL,
    NV_OF_ERR_INVALID_VERSION,
    NV_OF_ERR_OUT_OF_MEMORY,
    NV_OF_ERR_NOT_INITIALIZED,
    NV_OF_ERR_UNSUPPORTED_FEATURE,
    NV_OF_ERR_GENERIC,
} NV_OF_STATUS;

typedef enum _NV_OF_BOOL {
    NV_OF_FALSE = 0,
    NV_OF_TRUE = 1,
} NV_OF_BOOL;

typedef enum _NV_OF_CAPS {
    NV_OF_CAPS_SUPPORTED_OUTPUT_GRID_SIZES,
    NV_OF_CAPS_SUPPORTED_HINT_GRID_SIZES,
    NV_OF_CAPS_SUPPORT_HINT_WITH_OF_MODE,
    NV_OF_CAPS_SUPPORT_HINT_WITH_ST_MODE,
    NV_OF_CAPS_WIDTH_MIN,
    NV_OF_CAPS_HEIGHT_MIN,
    NV_OF_CAPS_WIDTH_MAX,
    NV_OF_CAPS_HEIGHT_MAX,
    NV_OF_CAPS_SUPPORT_ROI,
    NV_OF_CAPS_SUPPORT_ROI_MAX_NUM,
    NV_OF_CAPS_SUPPORT_STEREO,
    NV_OF_CAPS_SUPPORT_MAX
} NV_OF_CAPS;

typedef enum _NV_OF_PERF_LEVEL {
    NV_OF_PERF_LEVEL_UNDEFINED,
    NV_OF_PERF_LEVEL_SLOW = 5,
    NV_OF_PERF_LEVEL_MEDIUM = 10,
    NV_OF_PERF_LEVEL_FAST = 20,
    NV_OF_PERF_LEVEL_MAX
} NV_OF_PERF_LEVEL;

typedef enum _NV_OF_OUTPUT_VECTOR_GRID_SIZE {
    NV_OF_OUTPUT_VECTOR_GRID_SIZE_UNDEFINED,
    NV_OF_OUTPUT_VECTOR_GRID_SIZE_1 = 1,
    NV_OF_OUTPUT_VECTOR_GRID_SIZE_2 = 2,
    NV_OF_OUTPUT_VECTOR_GRID_SIZE_4 = 4,
    NV_OF_OUTPUT_VECTOR_GRID_SIZE_MAX
} NV_OF_OUTPUT_VECTOR_GRID_SIZE;

typedef enum _NV_OF_HINT_VECTOR_GRID_SIZE {
    NV_OF_HINT_VECTOR_GRID_SIZE_UNDEFINED,
    NV_OF_HINT_VECTOR_GRID_SIZE_1 = 1,
    NV_OF_HINT_VECTOR_GRID_SIZE_2 = 2,
    NV_OF_HINT_VECTOR_GRID_SIZE_4 = 4,
    NV_OF_HINT_VECTOR_GRID_SIZE_8 = 8,
    NV_OF_HINT_VECTOR_GRID_SIZE_MAX
} NV_OF_HINT_VECTOR_GRID_SIZE;

typedef enum _NV_OF_MODE {
    NV_OF_MODE_UNDEFINED,
    NV_OF_MODE_OPTICALFLOW,
    NV_OF_MODE_STEREODISPARITY,
    NV_OF_MODE_MAX
} NV_OF_MODE;

typedef enum _NV_OF_BUFFER_USAGE {
    NV_OF_BUFFER_USAGE_UNDEFINED,
    NV_OF_BUFFER_USAGE_INPUT,
    NV_OF_BUFFER_USAGE_OUTPUT,
    NV_OF_BUFFER_USAGE_HINT,
    NV_OF_BUFFER_USAGE_COST,
    NV_OF_BUFFER_USAGE_GLOBAL_FLOW,
    NV_OF_BUFFER_USAGE_MAX
} NV_OF_BUFFER_USAGE;

typedef enum _NV_OF_BUFFER_FORMAT {
    NV_OF_BUFFER_FORMAT_UNDEFINED,
    NV_OF_BUFFER_FORMAT_GRAYSCALE8,
    NV_OF_BUFFER_FORMAT_NV12,
    NV_OF_BUFFER_FORMAT_ABGR8,
    NV_OF_BUFFER_FORMAT_SHORT,
    NV_OF_BUFFER_FORMAT_SHORT2,
    NV_OF_BUFFER_FORMAT_UINT,
    NV_OF_BUFFER_FORMAT_UINT8,
    NV_OF_BUFFER_FORMAT_MAX
} NV_OF_BUFFER_FORMAT;

typedef enum _NV_OF_STEREO_DISPARITY_RANGE {
    NV_OF_STEREO_DISPARITY_RANGE_UNDEFINED,
    NV_OF_STEREO_DISPARITY_RANGE_128 = 128,
    NV_OF_STEREO_DISPARITY_RANGE_256 = 256,
    NV_OF_STEREO_DISPARITY_RANGE_MAX
} NV_OF_STEREO_DISPARITY_RANGE;

typedef enum _NV_OF_PRED_DIRECTION {
    NV_OF_PRED_DIRECTION_FORWARD = 0,
    NV_OF_PRED_DIRECTION_BOTH = 2,
    NV_OF_PRED_DIRECTION_MAX
} NV_OF_PRED_DIRECTION;

typedef struct _NV_OF_INIT_PARAMS {
    uint32_t width;
    uint32_t height;
    NV_OF_OUTPUT_VECTOR_GRID_SIZE outGridSize;
    NV_OF_HINT_VECTOR_GRID_SIZE hintGridSize;
    NV_OF_MODE mode;
    NV_OF_PERF_LEVEL perfLevel;
    NV_OF_BOOL enableExternalHints;
    NV_OF_BOOL enableOutputCost;
    NvOFPrivDataHandle hPrivData;
    NV_OF_STEREO_DISPARITY_RANGE disparityRange;
    NV_OF_BOOL enableRoi;
    NV_OF_PRED_DIRECTION predDirection;
    NV_OF_BOOL enableGlobalFlow;
    NV_OF_BUFFER_FORMAT inputBufferFormat;
} NV_OF_INIT_PARAMS;

typedef struct _NV_OF_ROI_RECT {
    uint32_t start_x;
    uint32_t start_y;
    uint32_t width;
    uint32_t height;
} NV_OF_ROI_RECT;

typedef struct _NV_OF_FENCE_POINT {
    ID3D12Fence* fence;
    uint64_t value;
} NV_OF_FENCE_POINT;

typedef struct _NV_OF_REGISTER_RESOURCE_PARAMS_D3D12 {
    ID3D12Resource* resource;
    NV_OF_FENCE_POINT inputFencePoint;
    NvOFGPUBufferHandle* hOFGpuBuffer;
    NV_OF_FENCE_POINT outputFencePoint;
} NV_OF_REGISTER_RESOURCE_PARAMS_D3D12;

typedef struct _NV_OF_UNREGISTER_RESOURCE_PARAMS_D3D12 {
    NvOFGPUBufferHandle hOFGpuBuffer;
} NV_OF_UNREGISTER_RESOURCE_PARAMS_D3D12;

typedef struct _NV_OF_EXECUTE_INPUT_PARAMS_D3D12 {
    NvOFGPUBufferHandle inputFrame;
    NvOFGPUBufferHandle referenceFrame;
    NvOFGPUBufferHandle externalHints;
    NV_OF_BOOL disableTemporalHints;
    uint32_t padding;
    NvOFPrivDataHandle hPrivData;
    uint32_t padding2;
    uint32_t numRois;
    NV_OF_ROI_RECT* roiData;
    uint32_t padding3;
    uint32_t numFencePoints;
    NV_OF_FENCE_POINT* fencePoint;
} NV_OF_EXECUTE_INPUT_PARAMS_D3D12;

typedef struct _NV_OF_EXECUTE_OUTPUT_PARAMS_D3D12 {
    NvOFGPUBufferHandle outputBuffer;
    NvOFGPUBufferHandle outputCostBuffer;
    NvOFPrivDataHandle hPrivData;
    NvOFGPUBufferHandle bwdOutputBuffer;
    NvOFGPUBufferHandle bwdOutputCostBuffer;
    NvOFGPUBufferHandle globalFlowBuffer;
    uint32_t padding[2];
    NV_OF_FENCE_POINT* fencePoint;
} NV_OF_EXECUTE_OUTPUT_PARAMS_D3D12;

typedef NV_OF_STATUS(NVOFAPI* PFNNVCREATEOPTICALFLOWD3D12)(ID3D12Device* const pD3D12Device, NvOFHandle* hOFInstance);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFINIT)(NvOFHandle hOf, const NV_OF_INIT_PARAMS* initParams);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFGETSURFACEFORMATCOUNTD3D12)(NvOFHandle hOf, const NV_OF_BUFFER_USAGE bufUsage,
                                                                 const NV_OF_MODE ofMode, uint32_t* const pCount);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFGETSURFACEFORMATD3D12)(NvOFHandle hOf, const NV_OF_BUFFER_USAGE bufUsage,
                                                            const NV_OF_MODE ofMode, DXGI_FORMAT* const pFormat);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFREGISTERRESOURCED3D12)(NvOFHandle hOf,
                                                            NV_OF_REGISTER_RESOURCE_PARAMS_D3D12* registerParams);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFUNREGISTERRESOURCED3D12)(NV_OF_UNREGISTER_RESOURCE_PARAMS_D3D12* unregisterParams);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFEXECUTED3D12)(NvOFHandle hOf,
                                                   const NV_OF_EXECUTE_INPUT_PARAMS_D3D12* executeInParams,
                                                   NV_OF_EXECUTE_OUTPUT_PARAMS_D3D12* executeOutParams);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFDESTROY)(NvOFHandle hOf);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFGETLASTERROR)(NvOFHandle hOf, char lastError[], uint32_t* size);
typedef NV_OF_STATUS(NVOFAPI* PFNNVOFGETCAPS)(NvOFHandle hOf, NV_OF_CAPS capsParam, uint32_t* capsVal, uint32_t* size);

typedef struct _NV_OF_D3D12_API_FUNCTION_LIST {
    PFNNVCREATEOPTICALFLOWD3D12 nvCreateOpticalFlowD3D12;
    PFNNVOFINIT nvOFInit;
    PFNNVOFGETSURFACEFORMATCOUNTD3D12 nvOFGetSurfaceFormatCountD3D12;
    PFNNVOFGETSURFACEFORMATD3D12 nvOFGetSurfaceFormatD3D12;
    PFNNVOFREGISTERRESOURCED3D12 nvOFRegisterResourceD3D12;
    PFNNVOFUNREGISTERRESOURCED3D12 nvOFUnregisterResourceD3D12;
    PFNNVOFEXECUTED3D12 nvOFExecuteD3D12;
    PFNNVOFDESTROY nvOFDestroy;
    PFNNVOFGETLASTERROR nvOFGetLastError;
    PFNNVOFGETCAPS nvOFGetCaps;
} NV_OF_D3D12_API_FUNCTION_LIST;

} // extern "C"
