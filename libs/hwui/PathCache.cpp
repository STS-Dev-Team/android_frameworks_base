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

#define LOG_TAG "OpenGLRenderer"

#include <GLES2/gl2.h>

#include <SkCanvas.h>
#include <SkRect.h>

#include <utils/threads.h>

#include "PathCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

PathCache::PathCache():
        mCache(GenerationCache<PathCacheEntry, PathTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_PATH_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_PATH_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting path cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default path cache size of %.2fMB", DEFAULT_PATH_CACHE_SIZE);
    }
    init();
}

PathCache::PathCache(uint32_t maxByteSize):
        mCache(GenerationCache<PathCacheEntry, PathTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize) {
    init();
}

PathCache::~PathCache() {
    mCache.clear();
}

void PathCache::init() {
    mCache.setOnEntryRemovedListener(this);

    GLint maxTextureSize;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    mMaxTextureSize = maxTextureSize;

    mDebugEnabled = readDebugLevel() & kDebugCaches;
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t PathCache::getSize() {
    return mSize;
}

uint32_t PathCache::getMaxSize() {
    return mMaxSize;
}

void PathCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void PathCache::operator()(PathCacheEntry& path, PathTexture*& texture) {
    removeTexture(texture);
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void PathCache::removeTexture(PathTexture* texture) {
    if (texture) {
        const uint32_t size = texture->width * texture->height;
        mSize -= size;

        PATH_LOGD("PathCache::callback: delete path: name, size, mSize = %d, %d, %d",
                texture->id, size, mSize);
        if (mDebugEnabled) {
            LOGD("Path deleted, size = %d", size);
        }

        glDeleteTextures(1, &texture->id);
        delete texture;
    }
}

void PathCache::remove(SkPath* path) {
    // TODO: Linear search...
    Vector<uint32_t> pathsToRemove;
    for (uint32_t i = 0; i < mCache.size(); i++) {
        if (mCache.getKeyAt(i).path == path) {
            pathsToRemove.push(i);
            removeTexture(mCache.getValueAt(i));
        }
    }

    mCache.setOnEntryRemovedListener(NULL);
    for (size_t i = 0; i < pathsToRemove.size(); i++) {
        mCache.removeAt(pathsToRemove.itemAt(i));
    }
    mCache.setOnEntryRemovedListener(this);
}

void PathCache::removeDeferred(SkPath* path) {
    Mutex::Autolock _l(mLock);
    mGarbage.push(path);
}

void PathCache::clearGarbage() {
    Mutex::Autolock _l(mLock);
    size_t count = mGarbage.size();
    for (size_t i = 0; i < count; i++) {
        remove(mGarbage.itemAt(i));
    }
    mGarbage.clear();
}

PathTexture* PathCache::get(SkPath* path, SkPaint* paint) {
    PathCacheEntry entry(path, paint);

    PathTexture* texture = mCache.get(entry);

    if (!texture) {
        texture = addTexture(entry, path, paint);
    } else if (path->getGenerationID() != texture->generation) {
        mCache.remove(entry);
        texture = addTexture(entry, path, paint);
    }

    return texture;
}

PathTexture* PathCache::addTexture(const PathCacheEntry& entry,
        const SkPath *path, const SkPaint* paint) {
    const SkRect& bounds = path->getBounds();

    const float pathWidth = fmax(bounds.width(), 1.0f);
    const float pathHeight = fmax(bounds.height(), 1.0f);

    if (pathWidth > mMaxTextureSize || pathHeight > mMaxTextureSize) {
        LOGW("Path too large to be rendered into a texture");
        return NULL;
    }

    const float offset = entry.strokeWidth * 1.5f;
    const uint32_t width = uint32_t(pathWidth + offset * 2.0 + 0.5);
    const uint32_t height = uint32_t(pathHeight + offset * 2.0 + 0.5);

    const uint32_t size = width * height;
    // Don't even try to cache a bitmap that's bigger than the cache
    if (size < mMaxSize) {
        while (mSize + size > mMaxSize) {
            mCache.removeOldest();
        }
    }

    PathTexture* texture = new PathTexture;
    texture->left = bounds.fLeft;
    texture->top = bounds.fTop;
    texture->offset = offset;
    texture->width = width;
    texture->height = height;
    texture->generation = path->getGenerationID();

    SkBitmap bitmap;
    bitmap.setConfig(SkBitmap::kA8_Config, width, height);
    bitmap.allocPixels();
    bitmap.eraseColor(0);

    SkPaint pathPaint(*paint);

    // Make sure the paint is opaque, color, alpha, filter, etc.
    // will be applied later when compositing the alpha8 texture
    pathPaint.setColor(0xff000000);
    pathPaint.setAlpha(255);
    pathPaint.setColorFilter(NULL);
    pathPaint.setMaskFilter(NULL);
    pathPaint.setShader(NULL);
    SkXfermode* mode = SkXfermode::Create(SkXfermode::kSrc_Mode);
    pathPaint.setXfermode(mode)->safeUnref();

    SkCanvas canvas(bitmap);
    canvas.translate(-bounds.fLeft + offset, -bounds.fTop + offset);
    canvas.drawPath(*path, pathPaint);

    generateTexture(bitmap, texture);

    if (size < mMaxSize) {
        mSize += size;
        PATH_LOGD("PathCache::get: create path: name, size, mSize = %d, %d, %d",
                texture->id, size, mSize);
        if (mDebugEnabled) {
            LOGD("Path created, size = %d", size);
        }
        mCache.put(entry, texture);
    } else {
        texture->cleanup = true;
    }

    return texture;
}

void PathCache::clear() {
    mCache.clear();
}

void PathCache::generateTexture(SkBitmap& bitmap, Texture* texture) {
    SkAutoLockPixels alp(bitmap);
    if (!bitmap.readyToDraw()) {
        LOGE("Cannot generate texture from bitmap");
        return;
    }

    glGenTextures(1, &texture->id);

    glBindTexture(GL_TEXTURE_2D, texture->id);
    // Textures are Alpha8
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    texture->blend = true;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, texture->width, texture->height, 0,
            GL_ALPHA, GL_UNSIGNED_BYTE, bitmap.getPixels());

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

}; // namespace uirenderer
}; // namespace android
