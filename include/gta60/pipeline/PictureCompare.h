#pragma once
// Repeated-picture detection with SSE4.1 capture-noise band and outlier tolerance.
// Ported from Likely7/Veyra-NRVideo PictureCompare.h.

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <emmintrin.h>
#include <intrin.h>
#include <smmintrin.h>
#include <vector>

namespace gta60::pipeline {

struct PicturePlane {
    const uint8_t* data = nullptr;
    size_t pitch = 0, rowBytes = 0;
    unsigned rows = 0;
};

enum class PictureComponents { Bytes, Words, Exact };

class PictureReference {
public:
    static constexpr unsigned kBand8 = 3; // +/-3 levels on 8-bit scale

    static constexpr unsigned bandFor(unsigned depth, unsigned shift) {
        return depth <= 8 ? kBand8 : ((kBand8 * ((1u << depth) - 1) + 127) / 255) << shift;
    }

    static constexpr uint64_t toleranceFor(uint64_t components) {
        return components / 196608;
    }

    static constexpr uint64_t kDecidedOver = 4096;

    struct Result {
        uint64_t id = 0;
        bool repeat = false;
        uint64_t over = 0;
        unsigned max8 = 0;
        bool commit = false;
    };

    explicit PictureReference(unsigned space = 0)
        : space_(static_cast<uint64_t>(space & 0xFFu) << 56) {}

    void reset() {
        valid_ = false;
        pending_ = false;
    }

    Result observe(const PicturePlane* planes, unsigned count, PictureComponents kind,
                   unsigned band = kBand8, bool deferCopy = false) {
        if (kind == PictureComponents::Exact) band = 0;
        Result r;
        size_t total = 0;
        for (unsigned p = 0; p < count; ++p) total += planes[p].rowBytes * planes[p].rows;
        const uint64_t tolerance = toleranceFor(kind == PictureComponents::Words ? total / 2 : total);

        if (valid_ && !pending_ && total == bytes_ && kind == kind_ && band == band_) {
            const uint64_t decided = std::max<uint64_t>(tolerance + 1, kDecidedOver);
            const uint8_t* ref = reference_.data();
            unsigned max = 0;
            for (unsigned p = 0; p < count && r.over < decided; ++p) {
                for (unsigned y = 0; y < planes[p].rows && r.over < decided; ++y, ref += planes[p].rowBytes) {
                    const uint8_t* row = planes[p].data + static_cast<size_t>(y) * planes[p].pitch;
                    if (kind == PictureComponents::Words) {
                        compareWords(row, ref, planes[p].rowBytes, band, r.over, max);
                    } else {
                        compareBytes(row, ref, planes[p].rowBytes, band, r.over, max);
                    }
                }
            }
            r.max8 = band ? (max * kBand8 + band / 2) / band : max;
            if (r.over <= tolerance) {
                r.id = space_ | id_;
                r.repeat = true;
                return r;
            }
        }

        bytes_ = total;
        kind_ = kind;
        band_ = band;
        id_ = (id_ + 1) & 0x00FFFFFFFFFFFFFFull;
        r.id = space_ | id_;
        if (deferCopy) {
            valid_ = false;
            pending_ = true;
            r.commit = true;
            return r;
        }
        copyReference(planes, count, total);
        return r;
    }

    void commit(const PicturePlane* planes, unsigned count) {
        if (!pending_) return;
        pending_ = false;
        size_t total = 0;
        for (unsigned p = 0; p < count; ++p) total += planes[p].rowBytes * planes[p].rows;
        if (total == bytes_) copyReference(planes, count, total);
    }

private:
    void copyReference(const PicturePlane* planes, unsigned count, size_t total) {
        if (reference_.size() < total) reference_.resize(total);
        uint8_t* ref = reference_.data();
        for (unsigned p = 0; p < count; ++p) {
            for (unsigned y = 0; y < planes[p].rows; ++y, ref += planes[p].rowBytes) {
                std::memcpy(ref, planes[p].data + static_cast<size_t>(y) * planes[p].pitch, planes[p].rowBytes);
            }
        }
        valid_ = true;
    }

    static void compareBytes(const uint8_t* a, const uint8_t* b, size_t n, unsigned band,
                             uint64_t& over, unsigned& max) {
        const __m128i limit = _mm_set1_epi8(static_cast<char>(band));
        const __m128i zero = _mm_setzero_si128();
        __m128i peak = zero;
        size_t i = 0;
        uint64_t count = 0;
        for (; i + 16 <= n; i += 16) {
            const __m128i x = _mm_loadu_si128(reinterpret_cast<const __m128i*>(a + i));
            const __m128i y = _mm_loadu_si128(reinterpret_cast<const __m128i*>(b + i));
            const __m128i d = _mm_or_si128(_mm_subs_epu8(x, y), _mm_subs_epu8(y, x));
            peak = _mm_max_epu8(peak, d);
            count += __popcnt(static_cast<unsigned>(~_mm_movemask_epi8(_mm_cmpeq_epi8(_mm_subs_epu8(d, limit), zero))) &
                              0xFFFFu);
        }
        for (; i < n; ++i) {
            const unsigned d = a[i] > b[i] ? a[i] - b[i] : b[i] - a[i];
            max = std::max(max, d);
            count += d > band;
        }
        alignas(16) uint8_t lanes[16];
        _mm_store_si128(reinterpret_cast<__m128i*>(lanes), peak);
        for (const uint8_t v : lanes) max = std::max<unsigned>(max, v);
        over += count;
    }

    static void compareWords(const uint8_t* a, const uint8_t* b, size_t n, unsigned band,
                             uint64_t& over, unsigned& max) {
        const __m128i limit = _mm_set1_epi16(static_cast<short>(band));
        const __m128i zero = _mm_setzero_si128();
        __m128i peak = zero;
        size_t i = 0;
        uint64_t count = 0;
        for (; i + 16 <= n; i += 16) {
            const __m128i x = _mm_loadu_si128(reinterpret_cast<const __m128i*>(a + i));
            const __m128i y = _mm_loadu_si128(reinterpret_cast<const __m128i*>(b + i));
            const __m128i d = _mm_or_si128(_mm_subs_epu16(x, y), _mm_subs_epu16(y, x));
            peak = _mm_max_epu16(peak, d);
            count += __popcnt(static_cast<unsigned>(~_mm_movemask_epi8(_mm_cmpeq_epi16(_mm_subs_epu16(d, limit), zero))) &
                              0xFFFFu) /
                     2;
        }
        for (; i + 2 <= n; i += 2) {
            const unsigned x = a[i] | (static_cast<unsigned>(a[i + 1]) << 8);
            const unsigned y = b[i] | (static_cast<unsigned>(b[i + 1]) << 8);
            const unsigned d = x > y ? x - y : y - x;
            max = std::max(max, d);
            count += d > band;
        }
        alignas(16) uint16_t lanes[8];
        _mm_store_si128(reinterpret_cast<__m128i*>(lanes), peak);
        for (const uint16_t v : lanes) max = std::max<unsigned>(max, v);
        over += count;
    }

    std::vector<uint8_t> reference_;
    size_t bytes_ = 0;
    uint64_t id_ = 0, space_ = 0;
    unsigned band_ = 0;
    bool valid_ = false, pending_ = false;
    PictureComponents kind_ = PictureComponents::Bytes;
};

} // namespace gta60::pipeline
