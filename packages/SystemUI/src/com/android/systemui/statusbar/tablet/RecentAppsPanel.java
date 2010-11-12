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

package com.android.systemui.statusbar.tablet;

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IThumbnailReceiver;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class RecentAppsPanel extends LinearLayout implements StatusBarPanel, OnClickListener {
    private static final String TAG = "RecentAppsPanel";
    private static final boolean DEBUG = TabletStatusBarService.DEBUG;
    private static final int DISPLAY_TASKS = 4; // number of recent tasks to display
    private static final int MAX_TASKS = 2 * DISPLAY_TASKS; // give some slack for non-apps
    private static final boolean DBG = true;
    private TabletStatusBarService mBar;
    private TextView mNoRecents;
    private LinearLayout mRecentsContainer;
    private ArrayList<ActivityDescription> mActivityDescriptions;

    static class ActivityDescription {
        int id;
        Bitmap thumbnail; // generated by Activity.onCreateThumbnail()
        Drawable icon; // application package icon
        String label; // application package label
        CharSequence description; // generated by Activity.onCreateDescription()
        Intent intent; // launch intent for application
        Matrix matrix; // arbitrary rotation matrix to correct orientation
        int position; // position in list

        public ActivityDescription(Bitmap _thumbnail,
                Drawable _icon, String _label, String _desc, Intent _intent, int _id, int _pos)
        {
            thumbnail = _thumbnail;
            icon = _icon;
            label = _label;
            description = _desc;
            intent = _intent;
            id = _id;
            position = _pos;
        }
    };

    private final IThumbnailReceiver mThumbnailReceiver = new IThumbnailReceiver.Stub() {

        public void finished() throws RemoteException {
        }

        public void newThumbnail(final int id, final Bitmap bitmap, CharSequence description)
                throws RemoteException {
            ActivityDescription info = findActivityDescription(id);
            if (info != null) {
                info.thumbnail = bitmap;
                info.description = description;
            }
        }
    };

    public boolean isInContentArea(int x, int y) {
        final int l = getPaddingLeft();
        final int r = getWidth() - getPaddingRight();
        final int t = getPaddingTop();
        final int b = getHeight() - getPaddingBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public void setBar(TabletStatusBarService bar) {
        mBar = bar;
    }

    public RecentAppsPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentAppsPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNoRecents = (TextView) findViewById(R.id.recents_no_recents);
        mRecentsContainer = (LinearLayout) findViewById(R.id.recents_container);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        Log.v(TAG, "onVisibilityChanged(" + changedView + ", " + visibility + ")");
        if (visibility == View.VISIBLE && changedView == this) {
            refreshApplicationList();
            mRecentsContainer.setScrollbarFadingEnabled(true);
            mRecentsContainer.scrollTo(0, 0);
        }
    }

    private ArrayList<ActivityDescription> getRecentTasks() {
        ArrayList<ActivityDescription> activityDescriptions = new ArrayList<ActivityDescription>();
        final PackageManager pm = mContext.getPackageManager();
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);

        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .resolveActivityInfo(pm, 0);

        int numTasks = recentTasks.size();
        for (int i = 0, index = 0; i < numTasks && (index < MAX_TASKS); ++i) {
            final ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);

            Intent intent = new Intent(recentInfo.baseIntent);
            if (recentInfo.origActivity != null) {
                intent.setComponent(recentInfo.origActivity);
            }

            // Skip the current home activity.
            if (homeInfo != null
                    && homeInfo.packageName.equals(intent.getComponent().getPackageName())
                    && homeInfo.name.equals(intent.getComponent().getClassName())) {
                continue;
            }

            intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
            if (resolveInfo != null) {
                final ActivityInfo info = resolveInfo.activityInfo;
                final String title = info.loadLabel(pm).toString();
                Drawable icon = info.loadIcon(pm);
                int id = recentTasks.get(i).id;
                if (title != null && title.length() > 0 && icon != null) {
                    Log.v(TAG, "creating activity desc for id=" + id + ", label=" + title);
                    ActivityDescription item = new ActivityDescription(
                            null, icon, title, null, intent, id, index);
                    activityDescriptions.add(item);
                    ++index;
                } else {
                    if (DBG) Log.v(TAG, "SKIPPING item " + id);
                }
            }
        }
        return activityDescriptions;
    }

    ActivityDescription findActivityDescription(int id)
    {
        ActivityDescription desc = null;
        for (int i = 0; i < mActivityDescriptions.size(); i++) {
            ActivityDescription item = mActivityDescriptions.get(i);
            if (item != null && item.id == id) {
                desc = item;
                break;
            }
        }
        return desc;
    }

    private void getThumbnails(ArrayList<ActivityDescription> tasks) {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningTaskInfo> runningTasks = am.getRunningTasks(MAX_TASKS, 0, mThumbnailReceiver);
        for (RunningTaskInfo r : runningTasks) {
            // Find the activity description associted with the given id
            ActivityDescription desc = findActivityDescription(r.id);
            if (desc != null) {
                if (r.thumbnail != null) {
                    desc.thumbnail = r.thumbnail;
                    desc.description = r.description;
                } else {
                    if (DBG) Log.v(TAG, "*** RUNNING THUMBNAIL WAS NULL ***");
                }
            } else {
                if (DBG) Log.v(TAG, "Couldn't find ActivityDesc for id=" + r.id);
            }
        }
    }

    private void refreshApplicationList() {
        mActivityDescriptions = getRecentTasks();
        getThumbnails(mActivityDescriptions);
        updateUiElements();
    }

    private void updateUiElements() {
        mRecentsContainer.removeAllViews();
        final int first = 0;
        final int last = Math.min(mActivityDescriptions.size(), DISPLAY_TASKS) - 1;
        for (int i = last; i >= first; i--) {
            ActivityDescription activityDescription = mActivityDescriptions.get(i);
            View view = View.inflate(mContext, R.layout.sysbar_panel_recent_item, null);
            ImageView appThumbnail = (ImageView) view.findViewById(R.id.app_thumbnail);
            ImageView appIcon = (ImageView) view.findViewById(R.id.app_icon);
            TextView appDescription = (TextView) view.findViewById(R.id.app_label);
            if (activityDescription.thumbnail != null) {
                Log.v(TAG, "thumbnail res = " + activityDescription.thumbnail.getWidth()
                        + "x" + activityDescription.thumbnail.getHeight());
            } else {
                Log.v(TAG, "thumbnail for " + activityDescription.label + " was null");
            }
            appThumbnail.setImageBitmap(activityDescription.thumbnail);
            appIcon.setImageDrawable(activityDescription.icon);
            appDescription.setText(activityDescription.label);
            view.setOnClickListener(this);
            view.setTag(activityDescription);
            Log.v(TAG, "Adding task: " + activityDescription.label);
            mRecentsContainer.addView(view);
        }

        int views = mRecentsContainer.getChildCount();
        mNoRecents.setVisibility(views == 0 ? View.VISIBLE : View.GONE);
        mRecentsContainer.setVisibility(views > 0 ? View.VISIBLE : View.GONE);
    }

    public void onClick(View v) {
        ActivityDescription ad = (ActivityDescription)v.getTag();
        if (ad.id >= 0) {
            // This is an active task; it should just go to the foreground.
            IActivityManager am = ActivityManagerNative.getDefault();
            try {
                am.moveTaskToFront(ad.id);
            } catch (RemoteException e) {
            }
        } else {
            Intent intent = ad.intent;
            if (DEBUG) Log.v(TAG, "Starting activity " + intent);
            getContext().startActivity(intent);
        }
        mBar.animateCollapse();
    }
}
