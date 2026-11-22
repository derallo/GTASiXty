#include "V4l2Capture.hpp"

#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <vector>

namespace gta60_linux {
namespace {

struct MmapBuffer {
    void* start = MAP_FAILED;
    size_t length = 0;
};

int xioctl(int fd, unsigned long request, void* arg) {
    int r;
    do {
        r = ::ioctl(fd, request, arg);
    } while (r == -1 && errno == EINTR);
    return r;
}

} // namespace

struct V4l2Capture::Impl {
    int fd = -1;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t fps = 60;
    uint32_t bytesPerLine = 0;
    CapturePixelFormat fmt = CapturePixelFormat::YUYV;
    std::vector<MmapBuffer> buffers;
    bool streaming = false;
    std::chrono::steady_clock::time_point t0 = std::chrono::steady_clock::now();
};

V4l2Capture::V4l2Capture() : p_(std::make_unique<Impl>()) {}
V4l2Capture::~V4l2Capture() { close(); }

bool V4l2Capture::open(const std::string& device, uint32_t width, uint32_t height, uint32_t fps) {
    close();

    p_->fd = ::open(device.c_str(), O_RDWR | O_NONBLOCK);
    if (p_->fd < 0) {
        std::fprintf(stderr, "[v4l2] failed to open %s: %s\n", device.c_str(), std::strerror(errno));
        return false;
    }

    v4l2_capability cap{};
    if (xioctl(p_->fd, VIDIOC_QUERYCAP, &cap) < 0 || !(cap.capabilities & V4L2_CAP_VIDEO_CAPTURE)) {
        std::fprintf(stderr, "[v4l2] %s is not a video capture device\n", device.c_str());
        close();
        return false;
    }

    // Prefer NV12, fall back to YUYV.
    v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = width;
    fmt.fmt.pix.height = height;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_NV12;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;

    if (xioctl(p_->fd, VIDIOC_S_FMT, &fmt) < 0 || fmt.fmt.pix.pixelformat != V4L2_PIX_FMT_NV12) {
        std::memset(&fmt, 0, sizeof(fmt));
        fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        fmt.fmt.pix.width = width;
        fmt.fmt.pix.height = height;
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
        fmt.fmt.pix.field = V4L2_FIELD_NONE;
        if (xioctl(p_->fd, VIDIOC_S_FMT, &fmt) < 0) {
            std::fprintf(stderr, "[v4l2] VIDIOC_S_FMT failed on %s: %s\n", device.c_str(), std::strerror(errno));
            close();
            return false;
        }
    }

    p_->width = fmt.fmt.pix.width;
    p_->height = fmt.fmt.pix.height;
    p_->bytesPerLine = fmt.fmt.pix.bytesperline;
    if (fmt.fmt.pix.pixelformat == V4L2_PIX_FMT_NV12) {
        p_->fmt = CapturePixelFormat::NV12;
    } else if (fmt.fmt.pix.pixelformat == V4L2_PIX_FMT_YUYV) {
        p_->fmt = CapturePixelFormat::YUYV;
    } else {
        std::fprintf(stderr, "[v4l2] unsupported negotiated fourcc 0x%08x\n", fmt.fmt.pix.pixelformat);
        close();
        return false;
    }

    v4l2_streamparm parm{};
    parm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    parm.parm.capture.timeperframe.numerator = 1;
    parm.parm.capture.timeperframe.denominator = fps;
    if (xioctl(p_->fd, VIDIOC_S_PARM, &parm) == 0 && parm.parm.capture.timeperframe.numerator > 0) {
        p_->fps = parm.parm.capture.timeperframe.denominator / parm.parm.capture.timeperframe.numerator;
    } else {
        p_->fps = fps;
    }

    // Request a minimal 2-buffer MMAP queue to keep capture latency low (driver bumps up if needed).
    v4l2_requestbuffers req{};
    req.count = 2;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(p_->fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 2) {
        std::fprintf(stderr, "[v4l2] VIDIOC_REQBUFS failed: %s\n", std::strerror(errno));
        close();
        return false;
    }

    p_->buffers.resize(req.count);
    for (uint32_t i = 0; i < req.count; ++i) {
        v4l2_buffer buf{};
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (xioctl(p_->fd, VIDIOC_QUERYBUF, &buf) < 0) {
            close();
            return false;
        }
        void* ptr = ::mmap(nullptr, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, p_->fd, buf.m.offset);
        if (ptr == MAP_FAILED) {
            close();
            return false;
        }
        p_->buffers[i] = {ptr, buf.length};
        if (xioctl(p_->fd, VIDIOC_QBUF, &buf) < 0) {
            close();
            return false;
        }
    }

    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(p_->fd, VIDIOC_STREAMON, &type) < 0) {
        std::fprintf(stderr, "[v4l2] VIDIOC_STREAMON failed: %s\n", std::strerror(errno));
        close();
        return false;
    }
    p_->streaming = true;
    p_->t0 = std::chrono::steady_clock::now();
    return true;
}

void V4l2Capture::close() {
    if (p_->fd >= 0 && p_->streaming) {
        v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        xioctl(p_->fd, VIDIOC_STREAMOFF, &type);
        p_->streaming = false;
    }
    for (auto& b : p_->buffers) {
        if (b.start != MAP_FAILED && b.length > 0) {
            ::munmap(b.start, b.length);
        }
    }
    p_->buffers.clear();
    if (p_->fd >= 0) {
        ::close(p_->fd);
        p_->fd = -1;
    }
}

uint32_t V4l2Capture::width() const { return p_->width; }
uint32_t V4l2Capture::height() const { return p_->height; }
uint32_t V4l2Capture::fps() const { return p_->fps; }
CapturePixelFormat V4l2Capture::format() const { return p_->fmt; }

bool V4l2Capture::grabFrame(int timeoutMs, const std::function<void(const CapturedFrame&)>& onFrame) {
    if (p_->fd < 0 || !p_->streaming) return false;

    pollfd pfd{p_->fd, POLLIN, 0};
    int pr = ::poll(&pfd, 1, timeoutMs);
    if (pr <= 0) return false;

    v4l2_buffer buf{};
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    if (xioctl(p_->fd, VIDIOC_DQBUF, &buf) < 0) {
        return false;
    }

    const auto now = std::chrono::steady_clock::now();
    const uint32_t tsMs = static_cast<uint32_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(now - p_->t0).count());

    if (onFrame && buf.index < p_->buffers.size()) {
        CapturedFrame cf{};
        cf.data = static_cast<const uint8_t*>(p_->buffers[buf.index].start);
        cf.size = buf.bytesused;
        cf.width = p_->width;
        cf.height = p_->height;
        cf.stride = p_->bytesPerLine;
        cf.format = p_->fmt;
        cf.timestampMs = tsMs;
        onFrame(cf);
    }

    xioctl(p_->fd, VIDIOC_QBUF, &buf);
    return true;
}

} // namespace gta60_linux
