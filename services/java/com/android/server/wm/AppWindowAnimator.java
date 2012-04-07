// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import android.graphics.Matrix;
import android.util.Slog;
import android.view.Surface;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import java.io.PrintWriter;

/**
 *
 */
public class AppWindowAnimator {

    final AppWindowToken mAppToken;
    final WindowManagerService mService;
    final WindowAnimator mAnimator;

    boolean animating;
    Animation animation;
    boolean animInitialized;
    boolean hasTransformation;
    final Transformation transformation = new Transformation();

    // Have we been asked to have this token keep the screen frozen?
    // Protect with mAnimator.
    boolean freezingScreen;

    // Offset to the window of all layers in the token, for use by
    // AppWindowToken animations.
    int animLayerAdjustment;

    // Special surface for thumbnail animation.
    Surface thumbnail;
    int thumbnailTransactionSeq;
    int thumbnailX;
    int thumbnailY;
    int thumbnailLayer;
    Animation thumbnailAnimation;
    final Transformation thumbnailTransformation = new Transformation();

    public AppWindowAnimator(final WindowManagerService service, final AppWindowToken atoken) {
        mService = service;
        mAppToken = atoken;
        mAnimator = service.mAnimator;
    }

    public void setAnimation(Animation anim, boolean initialized) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Setting animation in " + this + ": " + anim);
        animation = anim;
        animating = false;
        animInitialized = initialized;
        anim.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mService.mTransitionAnimationScale);
        int zorder = anim.getZAdjustment();
        int adj = 0;
        if (zorder == Animation.ZORDER_TOP) {
            adj = WindowManagerService.TYPE_LAYER_OFFSET;
        } else if (zorder == Animation.ZORDER_BOTTOM) {
            adj = -WindowManagerService.TYPE_LAYER_OFFSET;
        }

        if (animLayerAdjustment != adj) {
            animLayerAdjustment = adj;
            updateLayers();
        }
        // Start out animation gone if window is gone, or visible if window is visible.
        transformation.clear();
        transformation.setAlpha(mAppToken.reportedVisible ? 1 : 0);
        hasTransformation = true;
    }

    public void setDummyAnimation() {
        if (animation == null) {
            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Setting dummy animation in " + this);
            animation = WindowManagerService.sDummyAnimation;
            animInitialized = false;
        }
    }

    public void clearAnimation() {
        if (animation != null) {
            animation = null;
            animating = true;
            animInitialized = false;
        }
        clearThumbnail();
    }

    public void clearThumbnail() {
        if (thumbnail != null) {
            thumbnail.destroy();
            thumbnail = null;
        }
    }

    void updateLayers() {
        final int N = mAppToken.allAppWindows.size();
        final int adj = animLayerAdjustment;
        thumbnailLayer = -1;
        for (int i=0; i<N; i++) {
            final WindowState w = mAppToken.allAppWindows.get(i);
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            winAnimator.mAnimLayer = w.mLayer + adj;
            if (winAnimator.mAnimLayer > thumbnailLayer) {
                thumbnailLayer = winAnimator.mAnimLayer;
            }
            if (WindowManagerService.DEBUG_LAYERS) Slog.v(WindowManagerService.TAG, "Updating layer " + w + ": "
                    + winAnimator.mAnimLayer);
            if (w == mService.mInputMethodTarget && !mService.mInputMethodTargetWaitingAnim) {
                mService.setInputMethodAnimLayerAdjustment(adj);
            }
            if (w == mService.mWallpaperTarget && mService.mLowerWallpaperTarget == null) {
                mService.setWallpaperAnimLayerAdjustmentLocked(adj);
            }
        }
    }

    private void stepThumbnailAnimation(long currentTime) {
        thumbnailTransformation.clear();
        thumbnailAnimation.getTransformation(currentTime, thumbnailTransformation);
        thumbnailTransformation.getMatrix().preTranslate(thumbnailX, thumbnailY);
        final boolean screenAnimation = mAnimator.mScreenRotationAnimation != null
                && mAnimator.mScreenRotationAnimation.isAnimating();
        if (screenAnimation) {
            thumbnailTransformation.postCompose(
                    mAnimator.mScreenRotationAnimation.getEnterTransformation());
        }
        // cache often used attributes locally
        final float tmpFloats[] = mService.mTmpFloats;
        thumbnailTransformation.getMatrix().getValues(tmpFloats);
        if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(thumbnail,
                "thumbnail", "POS " + tmpFloats[Matrix.MTRANS_X]
                + ", " + tmpFloats[Matrix.MTRANS_Y], null);
        thumbnail.setPosition(tmpFloats[Matrix.MTRANS_X], tmpFloats[Matrix.MTRANS_Y]);
        if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(thumbnail,
                "thumbnail", "alpha=" + thumbnailTransformation.getAlpha()
                + " layer=" + thumbnailLayer
                + " matrix=[" + tmpFloats[Matrix.MSCALE_X]
                + "," + tmpFloats[Matrix.MSKEW_Y]
                + "][" + tmpFloats[Matrix.MSKEW_X]
                + "," + tmpFloats[Matrix.MSCALE_Y] + "]", null);
        thumbnail.setAlpha(thumbnailTransformation.getAlpha());
        // The thumbnail is layered below the window immediately above this
        // token's anim layer.
        thumbnail.setLayer(thumbnailLayer + WindowManagerService.WINDOW_LAYER_MULTIPLIER
                - WindowManagerService.LAYER_OFFSET_THUMBNAIL);
        thumbnail.setMatrix(tmpFloats[Matrix.MSCALE_X], tmpFloats[Matrix.MSKEW_Y],
                tmpFloats[Matrix.MSKEW_X], tmpFloats[Matrix.MSCALE_Y]);
    }

    private boolean stepAnimation(long currentTime) {
        if (animation == null) {
            return false;
        }
        transformation.clear();
        final boolean more = animation.getTransformation(currentTime, transformation);
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
            WindowManagerService.TAG, "Stepped animation in " + this +
            ": more=" + more + ", xform=" + transformation);
        if (!more) {
            animation = null;
            clearThumbnail();
            if (WindowManagerService.DEBUG_ANIM) Slog.v(
                WindowManagerService.TAG, "Finished animation in " + this +
                " @ " + currentTime);
        }
        hasTransformation = more;
        return more;
    }

    // This must be called while inside a transaction.
    boolean stepAnimationLocked(long currentTime, int dw, int dh) {
        if (mService.okToDisplay()) {
            // We will run animations as long as the display isn't frozen.

            if (animation == WindowManagerService.sDummyAnimation) {
                // This guy is going to animate, but not yet.  For now count
                // it as not animating for purposes of scheduling transactions;
                // when it is really time to animate, this will be set to
                // a real animation and the next call will execute normally.
                return false;
            }

            if ((mAppToken.allDrawn || animating || mAppToken.startingDisplayed)
                    && animation != null) {
                if (!animating) {
                    if (WindowManagerService.DEBUG_ANIM) Slog.v(
                        WindowManagerService.TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": dw=" + dw + " dh=" + dh
                        + " scale=" + mService.mTransitionAnimationScale
                        + " allDrawn=" + mAppToken.allDrawn + " animating=" + animating);
                    if (!animInitialized) {
                        animation.initialize(dw, dh, dw, dh);
                    }
                    animation.setStartTime(currentTime);
                    animating = true;
                    if (thumbnail != null) {
                        thumbnail.show();
                        thumbnailAnimation.setStartTime(currentTime);
                    }
                }
                if (stepAnimation(currentTime)) {
                    // animation isn't over, step any thumbnail and that's
                    // it for now.
                    if (thumbnail != null) {
                        stepThumbnailAnimation(currentTime);
                    }
                    return true;
                }
            }
        } else if (animation != null) {
            // If the display is frozen, and there is a pending animation,
            // clear it and make sure we run the cleanup code.
            animating = true;
            animation = null;
        }

        hasTransformation = false;

        if (!animating && animation == null) {
            return false;
        }

        mAnimator.mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
            mService.debugLayoutRepeats("AppWindowToken", mAnimator.mPendingLayoutChanges);
        }

        clearAnimation();
        animating = false;
        if (animLayerAdjustment != 0) {
            animLayerAdjustment = 0;
            updateLayers();
        }
        if (mService.mInputMethodTarget != null
                && mService.mInputMethodTarget.mAppToken == mAppToken) {
            mService.moveInputMethodWindowsIfNeededLocked(true);
        }

        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                WindowManagerService.TAG, "Animation done in " + this
                + ": reportedVisible=" + mAppToken.reportedVisible);

        transformation.clear();

        final int N = mAppToken.windows.size();
        for (int i=0; i<N; i++) {
            mAppToken.windows.get(i).mWinAnimator.finishExit();
        }
        mAppToken.updateReportedVisibilityLocked();

        return false;
    }

    boolean showAllWindowsLocked() {
        boolean isAnimating = false;
        final int NW = mAppToken.allAppWindows.size();
        for (int i=0; i<NW; i++) {
            WindowStateAnimator winAnimator = mAppToken.allAppWindows.get(i).mWinAnimator;
            if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG,
                    "performing show on: " + winAnimator);
            winAnimator.performShowLocked();
            isAnimating |= winAnimator.isAnimating();
        }
        return isAnimating;
    }

    void dump(PrintWriter pw, String prefix) {
        if (freezingScreen) {
            pw.print(prefix); pw.print(" freezingScreen="); pw.println(freezingScreen);
        }
        if (animating || animation != null) {
            pw.print(prefix); pw.print("animating="); pw.print(animating);
                    pw.print(" animation="); pw.println(animation);
        }
        if (hasTransformation) {
            pw.print(prefix); pw.print("XForm: ");
                    transformation.printShortString(pw);
                    pw.println();
        }
        if (animLayerAdjustment != 0) {
            pw.print(prefix); pw.print("animLayerAdjustment="); pw.println(animLayerAdjustment);
        }
        if (thumbnail != null) {
            pw.print(prefix); pw.print("thumbnail="); pw.print(thumbnail);
                    pw.print(" x="); pw.print(thumbnailX);
                    pw.print(" y="); pw.print(thumbnailY);
                    pw.print(" layer="); pw.println(thumbnailLayer);
            pw.print(prefix); pw.print("thumbnailAnimation="); pw.println(thumbnailAnimation);
            pw.print(prefix); pw.print("thumbnailTransformation=");
                    pw.println(thumbnailTransformation.toShortString());
        }
    }
}
