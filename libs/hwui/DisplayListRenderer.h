/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_UI_DISPLAY_LIST_RENDERER_H
#define ANDROID_UI_DISPLAY_LIST_RENDERER_H

#include <SkChunkAlloc.h>
#include <SkFlattenable.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkPictureFlat.h>
#include <SkRefCnt.h>
#include <SkTDArray.h>
#include <SkTSearch.h>

#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MIN_WRITER_SIZE 16384
#define HEAP_BLOCK_SIZE 4096

///////////////////////////////////////////////////////////////////////////////
// Helpers
///////////////////////////////////////////////////////////////////////////////

class PathHeap: public SkRefCnt {
public:
    PathHeap(): mHeap(64 * sizeof(SkPath)) {
    };

    PathHeap(SkFlattenableReadBuffer& buffer): mHeap(64 * sizeof(SkPath)) {
        int count = buffer.readS32();

        mPaths.setCount(count);
        SkPath** ptr = mPaths.begin();
        SkPath* p = (SkPath*) mHeap.allocThrow(count * sizeof(SkPath));

        for (int i = 0; i < count; i++) {
            new (p) SkPath;
            p->unflatten(buffer);
            *ptr++ = p;
            p++;
        }
    }

    ~PathHeap() {
        SkPath** iter = mPaths.begin();
        SkPath** stop = mPaths.end();
        while (iter < stop) {
            (*iter)->~SkPath();
            iter++;
        }
    }

    int append(const SkPath& path) {
        SkPath* p = (SkPath*) mHeap.allocThrow(sizeof(SkPath));
        new (p) SkPath(path);
        *mPaths.append() = p;
        return mPaths.count();
    }

    int count() const { return mPaths.count(); }

    const SkPath& operator[](int index) const {
        return *mPaths[index];
    }

    void flatten(SkFlattenableWriteBuffer& buffer) const {
        int count = mPaths.count();

        buffer.write32(count);
        SkPath** iter = mPaths.begin();
        SkPath** stop = mPaths.end();
        while (iter < stop) {
            (*iter)->flatten(buffer);
            iter++;
        }
    }

private:
    SkChunkAlloc mHeap;
    SkTDArray<SkPath*> mPaths;
};

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

/**
 * Records drawing commands in a display list for latter playback.
 */
class DisplayListRenderer: public OpenGLRenderer {
public:
    DisplayListRenderer();
    ~DisplayListRenderer();

    enum Op {
        AcquireContext,
        ReleaseContext,
        Save,
        Restore,
        RestoreToCount,
        SaveLayer,
        SaveLayerAlpha,
        Translate,
        Rotate,
        Scale,
        SetMatrix,
        ConcatMatrix,
        ClipRect,
        DrawBitmap,
        DrawBitmapMatrix,
        DrawBitmapRect,
        DrawPatch,
        DrawColor,
        DrawRect,
        DrawPath,
        DrawLines,
        DrawText,
        ResetShader,
        SetupShader,
        ResetColorFilter,
        SetupColorFilter,
        ResetShadow,
        SetupShadow
    };

    void acquireContext();
    void releaseContext();

    int save(int flags);
    void restore();
    void restoreToCount(int saveCount);

    int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* p, int flags);

    void translate(float dx, float dy);
    void rotate(float degrees);
    void scale(float sx, float sy);

    void setMatrix(SkMatrix* matrix);
    void concatMatrix(SkMatrix* matrix);

    bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);

    void drawBitmap(SkBitmap* bitmap, float left, float top, const SkPaint* paint);
    void drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix, const SkPaint* paint);
    void drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    void drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
            uint32_t width, uint32_t height, float left, float top, float right, float bottom,
            const SkPaint* paint);
    void drawColor(int color, SkXfermode::Mode mode);
    void drawRect(float left, float top, float right, float bottom, const SkPaint* paint);
    void drawPath(SkPath* path, SkPaint* paint);
    void drawLines(float* points, int count, const SkPaint* paint);
    void drawText(const char* text, int bytesCount, int count, float x, float y, SkPaint* paint);

    void resetShader();
    void setupShader(SkiaShader* shader);

    void resetColorFilter();
    void setupColorFilter(SkiaColorFilter* filter);

    void resetShadow();
    void setupShadow(float radius, float dx, float dy, int color);

    void reset();

private:
    inline void addOp(Op drawOp) {
        mWriter.writeInt(drawOp);
    }

    inline void addInt(int value) {
        mWriter.writeInt(value);
    }

    void addInts(const int32_t* values, uint32_t count) {
        for (uint32_t i = 0; i < count; i++) {
            mWriter.writeInt(values[i]);
        }
    }

    inline void addFloat(float value) {
        mWriter.writeScalar(value);
    }

    void addFloats(const float* values, int count) {
        for (int i = 0; i < count; i++) {
            mWriter.writeScalar(values[i]);
        }
    }

    inline void addPoint(float x, float y) {
        mWriter.writeScalar(x);
        mWriter.writeScalar(y);
    }

    inline void addBounds(float left, float top, float right, float bottom) {
        mWriter.writeScalar(left);
        mWriter.writeScalar(top);
        mWriter.writeScalar(right);
        mWriter.writeScalar(bottom);
    }

    inline void addText(const void* text, size_t byteLength) {
        mWriter.writeInt(byteLength);
        mWriter.writePad(text, byteLength);
    }

    inline void addPath(const SkPath* path) {
        if (mPathHeap == NULL) {
            mPathHeap = new PathHeap();
        }
        addInt(mPathHeap->append(*path));
    }

    int find(SkTDArray<const SkFlatPaint*>& paints, const SkPaint* paint);

    inline void addPaint(const SkPaint* paint) {
        addInt(find(mPaints, paint));
    }

    int find(SkTDArray<const SkFlatMatrix*>& matrices, const SkMatrix* matrix);

    inline void addMatrix(const SkMatrix* matrix) {
        addInt(find(mMatrices, matrix));
    }

    int find(SkTDArray<const SkFlatBitmap*>& bitmaps, const SkBitmap& bitmap);

    inline void addBitmap(const SkBitmap* bitmap) {
        addInt(find(mBitmaps, *bitmap));
    }

    SkChunkAlloc mHeap;

    int mBitmapIndex;
    SkTDArray<const SkFlatBitmap*> mBitmaps;

    int mMatrixIndex;
    SkTDArray<const SkFlatMatrix*> mMatrices;

    int mPaintIndex;
    SkTDArray<const SkFlatPaint*> mPaints;

    PathHeap* mPathHeap;
    SkWriter32 mWriter;

    SkRefCntRecorder mRCRecorder;
    SkRefCntRecorder mTFRecorder;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_DISPLAY_LIST_RENDERER_H
