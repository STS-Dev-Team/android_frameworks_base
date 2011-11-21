/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "AudioPlayer"
#include <utils/Log.h>
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
#include <time.h>
#endif

#include <binder/IPCThreadState.h>
#include <media/AudioTrack.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

#include "include/AwesomePlayer.h"

#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
/* The AudioHAL uses 40ms buffers for audio.  The audio clock
 * interpolator is optimized for this case.
 */
#define AUDIOHAL_BUFSIZE_USECS 40000
#define AUDIOHAL_BUF_TOLERANCE_USECS 5000
#define AUDIOHAL_BUFSIZE_THRESHOLD (AUDIOHAL_BUFSIZE_USECS + AUDIOHAL_BUF_TOLERANCE_USECS)

namespace omap_enhancement
{

    /** Implements and audio clock interpolator with elastic time.
     *
     * This device was inspired by the paper "Using a DLL to Filter
     * Time" (F. Adriaensen, 2005).[1] It is intended to be used in an
     * audio callback.  post_buffer() should be called at the
     * beginning of the callback.  The algorithm predicts when the
     * next callback will be and scales time to try and meet that.
     *
     * [1]  http://kokkinizita.linuxaudio.org/papers/usingdll.pdf
     */
    class TimeInterpolator
    {
    public:
        TimeInterpolator();
        ~TimeInterpolator();

        int64_t get_system_usecs();
        int64_t get_stream_usecs();
        void post_buffer(int64_t frame_usecs);
        void reset(int64_t media_time = 0);
        void pause();
        void resume();
        int64_t usecs_queued() {
            return m_queued;
        }

    private:
        /* All time variables are in microseconds (usecs). */
        double m_Tf;      /* time scaling factor */
        int64_t m_t0;     /* time measured from here (epoch) */
        int64_t m_t1;     /* estimated time of next callback */
        int64_t m_pos0;   /* media position at t0 */
        int64_t m_queued; /* amount of media queued for next callback */
        pthread_mutex_t m_mutex;
        int64_t m_last;   /* the last timestamp reported to anyone */
    }; // class TimeInterpolator


    TimeInterpolator::TimeInterpolator()
    {
        pthread_mutex_init(&m_mutex, 0);
        reset();
    }

    TimeInterpolator::~TimeInterpolator()
    {
    }

    void TimeInterpolator::reset(int64_t media_time)
    {
        pthread_mutex_lock(&m_mutex);
        m_last = media_time;
        m_pos0 = media_time;
        m_queued = 0;
        m_t0 = get_system_usecs();
        m_t1 = m_t0 + AUDIOHAL_BUFSIZE_USECS;
        m_Tf = 1.0;
        pthread_mutex_unlock(&m_mutex);
    }

    void TimeInterpolator::pause()
    {
    }

    void TimeInterpolator::resume()
    {
        pthread_mutex_lock(&m_mutex);
        m_pos0 += m_queued;
        m_last = m_pos0;
        m_queued = 0;
        m_t0 = get_system_usecs();
        m_t1 = m_t0 + AUDIOHAL_BUFSIZE_USECS;
        m_Tf = 1.0;
        pthread_mutex_unlock(&m_mutex);
    }

    int64_t TimeInterpolator::get_system_usecs()
    {
        struct timespec tv;
        clock_gettime(CLOCK_MONOTONIC, &tv);
        return ((int64_t)tv.tv_sec * 1000000L + tv.tv_nsec / 1000);
    }

    /* t = m_pos0 + (now - m_t0) * m_Tf
     */
    int64_t TimeInterpolator::get_stream_usecs()
    {
        int64_t now;
        double dt;
        int64_t t_media;
        pthread_mutex_lock(&m_mutex);

        /* if m_queued == 0, we're starting up or paused */
        if (!m_queued) {
            t_media = m_pos0;
            goto end;
        }

        now = get_system_usecs();
        dt = m_Tf * double(now - m_t0);
        if (dt < 0.0) dt = 0.0;
        t_media = m_pos0 + int64_t(dt);
        if (t_media < m_last) LOGI("time is rewinding: %lld", t_media - m_last);
        m_last = t_media;

    end:
        pthread_mutex_unlock(&m_mutex);
        return t_media;
    }

    /* e = now - m_t1  (feedback)
     * m_t0 = now  (or t1, or valued based on m_last, if later)
     * m_t1 = now + frame_usecs - e/2
     * m_Tf = frame_usecs / (m_t1 - m_t0)
     *
     * For sanity/stability:
     * m_Tf is limited to the range [.9, 1.1] for sanity.
     * e is limited to the range [-frame_usecs, frame_usecs]
     */
    void TimeInterpolator::post_buffer(int64_t frame_usecs)
    {
        int64_t now;    /* The current system timestamp */
        int64_t dt;     /* The actual time since m_t0 */
        int64_t e = 0;  /* error value for our t1 estimation */
        int64_t last;   /* used to keep us monotonic */
        bool aggregate_buffers = false; /* see below */

        pthread_mutex_lock(&m_mutex);
        now = get_system_usecs();
        dt = now - m_t0;
        /* if m_queued == 0 then we are ramping up or paused */
        if (m_queued && (dt < (frame_usecs/4))) aggregate_buffers = true;

        if (!aggregate_buffers) {
            /* Predict the next callback.
             *
             * e tests how close our last prediction was, and is used
             * as a feedback for our next prediction.
             */
            last = m_t0 + int64_t((m_last - m_pos0)/m_Tf);
            last = 0;
            m_t0 = (now > last) ? now : last;
            e = now - m_t1;
            if (e > frame_usecs)
                e = frame_usecs;
            else if (-e > frame_usecs)
                e = -frame_usecs;
            m_t1 = now + frame_usecs - e;
            if (m_t1 - m_t0 < 1000) m_t1 = m_t0 + 1000;
            m_Tf = double(frame_usecs) / double(m_t1 - m_t0);
            if (m_Tf > 1.1)
                m_Tf = 1.1;
            else if (m_Tf < .9)
                m_Tf = .9;

            m_pos0 += m_queued;
            m_queued = frame_usecs;
        } else {
            /* If aggregate_buffers == true, then this call is very
             * close in time to the previous call.  Therefore we will
             * combine the data with the previous call(s) and treat
             * them as if they are one.
             */
            m_queued += frame_usecs;
            m_t1 += frame_usecs;
            m_Tf = double(m_queued) / double(m_t1 - m_t0);
            if (m_Tf > 1.1)
                m_Tf = 1.1;
            else if (m_Tf < .9)
                m_Tf = .9;
        }
        assert( m_t1 > m_t0 );
        pthread_mutex_unlock(&m_mutex);
    }

} /* namespace omap_enhancement */


#endif /*  defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4) */

namespace android {

AudioPlayer::AudioPlayer(
        const sp<MediaPlayerBase::AudioSink> &audioSink,
        AwesomePlayer *observer)
    : mAudioTrack(NULL),
      mInputBuffer(NULL),
      mSampleRate(0),
      mLatencyUs(0),
      mFrameSize(0),
      mNumFramesPlayed(0),
      mPositionTimeMediaUs(-1),
      mPositionTimeRealUs(-1),
      mSeeking(false),
      mReachedEOS(false),
      mFinalStatus(OK),
      mStarted(false),
      mIsFirstBuffer(false),
      mFirstBufferResult(OK),
      mFirstBuffer(NULL),
      mAudioSink(audioSink),
      mObserver(observer) {
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator = new omap_enhancement::TimeInterpolator;
#endif
}

AudioPlayer::~AudioPlayer() {
    if (mStarted) {
        reset();
    }
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    delete mRealTimeInterpolator;
#endif
}

void AudioPlayer::setSource(const sp<MediaSource> &source) {
    CHECK(mSource == NULL);
    mSource = source;
}

status_t AudioPlayer::start(bool sourceAlreadyStarted) {
    CHECK(!mStarted);
    CHECK(mSource != NULL);

#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator->reset();
#endif


    status_t err;
    if (!sourceAlreadyStarted) {
        err = mSource->start();

        if (err != OK) {
            return err;
        }
    }

    // We allow an optional INFO_FORMAT_CHANGED at the very beginning
    // of playback, if there is one, getFormat below will retrieve the
    // updated format, if there isn't, we'll stash away the valid buffer
    // of data to be used on the first audio callback.

    CHECK(mFirstBuffer == NULL);

    MediaSource::ReadOptions options;
    if (mSeeking) {
        options.setSeekTo(mSeekTimeUs);
        mSeeking = false;
    }

    mFirstBufferResult = mSource->read(&mFirstBuffer, &options);
    if (mFirstBufferResult == INFO_FORMAT_CHANGED) {
        LOGV("INFO_FORMAT_CHANGED!!!");

        CHECK(mFirstBuffer == NULL);
        mFirstBufferResult = OK;
        mIsFirstBuffer = false;
    } else {
        mIsFirstBuffer = true;
    }

    sp<MetaData> format = mSource->getFormat();
    const char *mime;
    bool success = format->findCString(kKeyMIMEType, &mime);
    CHECK(success);
    CHECK(!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_RAW));

    success = format->findInt32(kKeySampleRate, &mSampleRate);
    CHECK(success);

    int32_t numChannels;
    success = format->findInt32(kKeyChannelCount, &numChannels);
    CHECK(success);

    if (mAudioSink.get() != NULL) {
        status_t err = mAudioSink->open(
                mSampleRate, numChannels, AUDIO_FORMAT_PCM_16_BIT,
                DEFAULT_AUDIOSINK_BUFFERCOUNT,
                &AudioPlayer::AudioSinkCallback, this);
        if (err != OK) {
            if (mFirstBuffer != NULL) {
                mFirstBuffer->release();
                mFirstBuffer = NULL;
            }

            if (!sourceAlreadyStarted) {
                mSource->stop();
            }

            return err;
        }

        mLatencyUs = (int64_t)mAudioSink->latency() * 1000;
        mFrameSize = mAudioSink->frameSize();

        mAudioSink->start();
    } else {
        mAudioTrack = new AudioTrack(
                AUDIO_STREAM_MUSIC, mSampleRate, AUDIO_FORMAT_PCM_16_BIT,
                (numChannels == 2)
                    ? AUDIO_CHANNEL_OUT_STEREO
                    : AUDIO_CHANNEL_OUT_MONO,
                0, 0, &AudioCallback, this, 0);

        if ((err = mAudioTrack->initCheck()) != OK) {
            delete mAudioTrack;
            mAudioTrack = NULL;

            if (mFirstBuffer != NULL) {
                mFirstBuffer->release();
                mFirstBuffer = NULL;
            }

            if (!sourceAlreadyStarted) {
                mSource->stop();
            }

            return err;
        }

        mLatencyUs = (int64_t)mAudioTrack->latency() * 1000;
        mFrameSize = mAudioTrack->frameSize();

        mAudioTrack->start();
    }

    mStarted = true;

    return OK;
}

void AudioPlayer::pause(bool playPendingSamples) {
    CHECK(mStarted);

    if (playPendingSamples) {
        if (mAudioSink.get() != NULL) {
            mAudioSink->stop();
        } else {
            mAudioTrack->stop();
        }

        mNumFramesPlayed = 0;
    } else {
        if (mAudioSink.get() != NULL) {
            mAudioSink->pause();
        } else {
            mAudioTrack->pause();
        }
    }
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator->pause();
#endif
}

void AudioPlayer::resume() {
    CHECK(mStarted);

#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator->resume();
#endif

    if (mAudioSink.get() != NULL) {
        mAudioSink->start();
    } else {
        mAudioTrack->start();
    }
}

void AudioPlayer::reset() {
    CHECK(mStarted);

    if (mAudioSink.get() != NULL) {
        mAudioSink->stop();
        mAudioSink->close();
    } else {
        mAudioTrack->stop();

        delete mAudioTrack;
        mAudioTrack = NULL;
    }

    // Make sure to release any buffer we hold onto so that the
    // source is able to stop().

    if (mFirstBuffer != NULL) {
        mFirstBuffer->release();
        mFirstBuffer = NULL;
    }

    if (mInputBuffer != NULL) {
        LOGV("AudioPlayer releasing input buffer.");

        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    mSource->stop();

    // The following hack is necessary to ensure that the OMX
    // component is completely released by the time we may try
    // to instantiate it again.
    wp<MediaSource> tmp = mSource;
    mSource.clear();
    while (tmp.promote() != NULL) {
        usleep(1000);
    }
    IPCThreadState::self()->flushCommands();

    mNumFramesPlayed = 0;
    mPositionTimeMediaUs = -1;
    mPositionTimeRealUs = -1;
    mSeeking = false;
    mReachedEOS = false;
    mFinalStatus = OK;
    mStarted = false;
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator->reset();
#endif
}

// static
void AudioPlayer::AudioCallback(int event, void *user, void *info) {
    static_cast<AudioPlayer *>(user)->AudioCallback(event, info);
}

bool AudioPlayer::isSeeking() {
    Mutex::Autolock autoLock(mLock);
    return mSeeking;
}

bool AudioPlayer::reachedEOS(status_t *finalStatus) {
    *finalStatus = OK;

    Mutex::Autolock autoLock(mLock);
    *finalStatus = mFinalStatus;
    return mReachedEOS;
}

// static
size_t AudioPlayer::AudioSinkCallback(
        MediaPlayerBase::AudioSink *audioSink,
        void *buffer, size_t size, void *cookie) {
    AudioPlayer *me = (AudioPlayer *)cookie;

    return me->fillBuffer(buffer, size);
}

void AudioPlayer::AudioCallback(int event, void *info) {
    if (event != AudioTrack::EVENT_MORE_DATA) {
        return;
    }

    AudioTrack::Buffer *buffer = (AudioTrack::Buffer *)info;
    size_t numBytesWritten = fillBuffer(buffer->raw, buffer->size);

    buffer->size = numBytesWritten;
}

uint32_t AudioPlayer::getNumFramesPendingPlayout() const {
    uint32_t numFramesPlayedOut;
    status_t err;

    if (mAudioSink != NULL) {
        err = mAudioSink->getPosition(&numFramesPlayedOut);
    } else {
        err = mAudioTrack->getPosition(&numFramesPlayedOut);
    }

    if (err != OK || mNumFramesPlayed < numFramesPlayedOut) {
        return 0;
    }

    // mNumFramesPlayed is the number of frames submitted
    // to the audio sink for playback, but not all of them
    // may have played out by now.
    return mNumFramesPlayed - numFramesPlayedOut;
}

size_t AudioPlayer::fillBuffer(void *data, size_t size) {
    if (mNumFramesPlayed == 0) {
        LOGV("AudioCallback");
    }

    if (mReachedEOS) {
        return 0;
    }

    bool postSeekComplete = false;
    bool postEOS = false;
    int64_t postEOSDelayUs = 0;

#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator->post_buffer( ((size / mFrameSize) * 1000000) / mSampleRate );
    mPositionTimeRealUs = mRealTimeInterpolator->get_stream_usecs();
#endif // defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)

    size_t size_done = 0;
    size_t size_remaining = size;
    while (size_remaining > 0) {
        MediaSource::ReadOptions options;

        {
            Mutex::Autolock autoLock(mLock);

            if (mSeeking) {
                if (mIsFirstBuffer) {
                    if (mFirstBuffer != NULL) {
                        mFirstBuffer->release();
                        mFirstBuffer = NULL;
                    }
                    mIsFirstBuffer = false;
                }

                options.setSeekTo(mSeekTimeUs);

                if (mInputBuffer != NULL) {
                    mInputBuffer->release();
                    mInputBuffer = NULL;
                }

                mSeeking = false;
                if (mObserver) {
                    postSeekComplete = true;
                }
            }
        }

        if (mInputBuffer == NULL) {
            status_t err;

            if (mIsFirstBuffer) {
                mInputBuffer = mFirstBuffer;
                mFirstBuffer = NULL;
                err = mFirstBufferResult;
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
                mRealTimeInterpolator->reset();
#endif

                mIsFirstBuffer = false;
            } else {
                err = mSource->read(&mInputBuffer, &options);
            }

            CHECK((err == OK && mInputBuffer != NULL)
                   || (err != OK && mInputBuffer == NULL));

            Mutex::Autolock autoLock(mLock);

            if (err != OK) {
                if (mObserver && !mReachedEOS) {
                    // We don't want to post EOS right away but only
                    // after all frames have actually been played out.

                    // These are the number of frames submitted to the
                    // AudioTrack that you haven't heard yet.
                    uint32_t numFramesPendingPlayout =
                        getNumFramesPendingPlayout();

                    // These are the number of frames we're going to
                    // submit to the AudioTrack by returning from this
                    // callback.
                    uint32_t numAdditionalFrames = size_done / mFrameSize;

                    numFramesPendingPlayout += numAdditionalFrames;

                    int64_t timeToCompletionUs =
                        (1000000ll * numFramesPendingPlayout) / mSampleRate;

                    LOGV("total number of frames played: %lld (%lld us)",
                            (mNumFramesPlayed + numAdditionalFrames),
                            1000000ll * (mNumFramesPlayed + numAdditionalFrames)
                                / mSampleRate);

                    LOGV("%d frames left to play, %lld us (%.2f secs)",
                         numFramesPendingPlayout,
                         timeToCompletionUs, timeToCompletionUs / 1E6);

                    postEOS = true;
                    postEOSDelayUs = timeToCompletionUs + mLatencyUs;
                }

                mReachedEOS = true;
                mFinalStatus = err;
                break;
            }

            CHECK(mInputBuffer->meta_data()->findInt64(
                        kKeyTime, &mPositionTimeMediaUs));

#if !(defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4))
            mPositionTimeRealUs =
                ((mNumFramesPlayed + size_done / mFrameSize) * 1000000)
                    / mSampleRate;
#endif

            LOGV("buffer->size() = %d, "
                 "mPositionTimeMediaUs=%.2f mPositionTimeRealUs=%.2f",
                 mInputBuffer->range_length(),
                 mPositionTimeMediaUs / 1E6, mPositionTimeRealUs / 1E6);
        }

        if (mInputBuffer->range_length() == 0) {
            mInputBuffer->release();
            mInputBuffer = NULL;

            continue;
        }

        size_t copy = size_remaining;
        if (copy > mInputBuffer->range_length()) {
            copy = mInputBuffer->range_length();
        }

        memcpy((char *)data + size_done,
               (const char *)mInputBuffer->data() + mInputBuffer->range_offset(),
               copy);

        mInputBuffer->set_range(mInputBuffer->range_offset() + copy,
                                mInputBuffer->range_length() - copy);

        size_done += copy;
        size_remaining -= copy;
    }

#if !(defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4))
    {
        Mutex::Autolock autoLock(mLock);
        mNumFramesPlayed += size_done / mFrameSize;
    }
#endif

    if (postEOS) {
        mObserver->postAudioEOS(postEOSDelayUs);
    }

    if (postSeekComplete) {
        mObserver->postAudioSeekComplete();
    }

    return size_done;
}

int64_t AudioPlayer::getRealTimeUs() {
    Mutex::Autolock autoLock(mLock);
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    return mRealTimeInterpolator->get_stream_usecs() - mLatencyUs;
#else
    return getRealTimeUsLocked();
#endif

}

int64_t AudioPlayer::getRealTimeUsLocked() const {
    CHECK(mStarted);
    CHECK_NE(mSampleRate, 0);
    return -mLatencyUs + (mNumFramesPlayed * 1000000) / mSampleRate;
}

int64_t AudioPlayer::getMediaTimeUs() {
    Mutex::Autolock autoLock(mLock);

    if (mPositionTimeMediaUs < 0 || mPositionTimeRealUs < 0) {
        if (mSeeking) {
            return mSeekTimeUs;
        }

        return 0;
    }

    int64_t realTimeOffset = getRealTimeUsLocked() - mPositionTimeRealUs;
    if (realTimeOffset < 0) {
        realTimeOffset = 0;
    }

    return mPositionTimeMediaUs + realTimeOffset;
}

bool AudioPlayer::getMediaTimeMapping(
        int64_t *realtime_us, int64_t *mediatime_us) {
    Mutex::Autolock autoLock(mLock);

    *realtime_us = mPositionTimeRealUs;
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    /* AwesomePlayer is trying to use the media read pointer as an
     * accurate indication of the "audio time" as an attempt to check
     * that the file pointer isn't too far from the audio clock
     * (getRealTimeUs()).  Since we are handling this internally, we
     * return the same value as long as we are within expected limits.
     */
    int64_t now = mRealTimeInterpolator->get_stream_usecs();
    int64_t audio_queued = mRealTimeInterpolator->usecs_queued();
    if ((mPositionTimeMediaUs <= now + audio_queued)
        && (mPositionTimeMediaUs >= now) ) {
        *mediatime_us = now;
    } else {
        *mediatime_us = mPositionTimeMediaUs;
    }
#else
    *mediatime_us = mPositionTimeMediaUs;
#endif

    return mPositionTimeRealUs != -1 && mPositionTimeMediaUs != -1;
}

status_t AudioPlayer::seekTo(int64_t time_us) {
    Mutex::Autolock autoLock(mLock);

    mSeeking = true;
    mPositionTimeRealUs = mPositionTimeMediaUs = -1;
    mReachedEOS = false;
    mSeekTimeUs = time_us;
#if defined(OMAP_ENHANCEMENT) && defined(TARGET_OMAP4)
    mRealTimeInterpolator->reset(time_us);
#endif

    // Flush resets the number of played frames
    mNumFramesPlayed = 0;

    if (mAudioSink != NULL) {
        mAudioSink->flush();
    } else {
        mAudioTrack->flush();
    }

    return OK;
}

}
