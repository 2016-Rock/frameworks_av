/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.widget;

import android.content.res.Resources;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.update.MediaControlView2Provider;
import android.media.update.ViewGroupProvider;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaControlView2;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.media.update.ApiHelper;
import com.android.media.update.R;
import com.android.support.mediarouter.app.MediaRouteButton;
import com.android.support.mediarouter.media.MediaRouter;
import com.android.support.mediarouter.media.MediaRouteSelector;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

public class MediaControlView2Impl extends BaseLayout implements MediaControlView2Provider {
    private static final String TAG = "MediaControlView2";

    private final MediaControlView2 mInstance;

    static final String ARGUMENT_KEY_FULLSCREEN = "fullScreen";

    // TODO: Move these constants to public api to support custom video view.
    static final String KEY_STATE_CONTAINS_SUBTITLE = "StateContainsSubtitle";
    static final String EVENT_UPDATE_SUBTITLE_STATUS = "UpdateSubtitleStatus";

    // TODO: Remove this once integrating with MediaSession2 & MediaMetadata2
    static final String KEY_STATE_IS_ADVERTISEMENT = "MediaTypeAdvertisement";
    static final String EVENT_UPDATE_MEDIA_TYPE_STATUS = "UpdateMediaTypeStatus";

    private static final int MAX_PROGRESS = 1000;
    private static final int DEFAULT_PROGRESS_UPDATE_TIME_MS = 1000;
    private static final int REWIND_TIME_MS = 10000;
    private static final int FORWARD_TIME_MS = 30000;
    private static final int AD_SKIP_WAIT_TIME_MS = 5000;
    private static final int RESOURCE_NON_EXISTENT = -1;

    private Resources mResources;
    private MediaController mController;
    private MediaController.TransportControls mControls;
    private PlaybackState mPlaybackState;
    private MediaMetadata mMetadata;
    private ProgressBar mProgress;
    private TextView mEndTime, mCurrentTime;
    private TextView mTitleView;
    private TextView mAdSkipView, mAdRemainingView;
    private View mAdExternalLink;
    private View mRoot;
    private int mDuration;
    private int mPrevState;
    private int mPrevLeftBarWidth;
    private long mPlaybackActions;
    private boolean mDragging;
    private boolean mIsFullScreen;
    private boolean mOverflowExpanded;
    private boolean mIsStopped;
    private boolean mSubtitleIsEnabled;
    private boolean mContainsSubtitle;
    private boolean mSeekAvailable;
    private boolean mIsAdvertisement;
    private ImageButton mPlayPauseButton;
    private ImageButton mFfwdButton;
    private ImageButton mRewButton;
    private ImageButton mNextButton;
    private ImageButton mPrevButton;

    private ViewGroup mBasicControls;
    private ImageButton mSubtitleButton;
    private ImageButton mFullScreenButton;
    private ImageButton mOverflowButtonRight;

    private ViewGroup mExtraControls;
    private ViewGroup mCustomButtons;
    private ImageButton mOverflowButtonLeft;
    private ImageButton mMuteButton;
    private ImageButton mAspectRationButton;
    private ImageButton mSettingsButton;

    private PopupWindow mSettingsWindow;
    private SettingsAdapter mSettingsAdapter;
    private List<Integer> mSettingsMainTextIdsList;
    private List<Integer> mSettingsSubTextIdsList;
    private List<Integer> mSettingsIconIdsList;

    private CharSequence mPlayDescription;
    private CharSequence mPauseDescription;
    private CharSequence mReplayDescription;

    private StringBuilder mFormatBuilder;
    private Formatter mFormatter;

    private MediaRouteButton mRouteButton;
    private MediaRouteSelector mRouteSelector;

    public MediaControlView2Impl(MediaControlView2 instance,
            ViewGroupProvider superProvider, ViewGroupProvider privateProvider) {
        super(instance, superProvider, privateProvider);
        mInstance = instance;
    }

    @Override
    public void initialize(@Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        mResources = ApiHelper.getLibResources();
        // Inflate MediaControlView2 from XML
        mRoot = makeControllerView();
        mRoot.addOnLayoutChangeListener(mTitleBarLayoutChangeListener);
        mInstance.addView(mRoot);
    }

    @Override
    public void setController_impl(MediaController controller) {
        mController = controller;
        if (controller != null) {
            mControls = controller.getTransportControls();
            // Set mMetadata and mPlaybackState to existing MediaSession variables since they may
            // be called before the callback is called
            mPlaybackState = mController.getPlaybackState();
            mMetadata = mController.getMetadata();
            updateDuration();
            updateTitle();

            mController.registerCallback(new MediaControllerCallback());
        }
    }

    @Override
    public void setButtonVisibility_impl(int button, int visibility) {
        // TODO: add member variables for Fast-Forward/Prvious/Rewind buttons to save visibility in
        // order to prevent being overriden inside updateLayout().
        switch (button) {
            case MediaControlView2.BUTTON_PLAY_PAUSE:
                if (mPlayPauseButton != null && canPause()) {
                    mPlayPauseButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_FFWD:
                if (mFfwdButton != null && canSeekForward()) {
                    mFfwdButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_REW:
                if (mRewButton != null && canSeekBackward()) {
                    mRewButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_NEXT:
                // TODO: this button is not visible unless its listener is manually set. Should this
                // function still be provided?
                if (mNextButton != null) {
                    mNextButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_PREV:
                // TODO: this button is not visible unless its listener is manually set. Should this
                // function still be provided?
                if (mPrevButton != null) {
                    mPrevButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_SUBTITLE:
                if (mSubtitleButton != null && mContainsSubtitle) {
                    mSubtitleButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_FULL_SCREEN:
                if (mFullScreenButton != null) {
                    mFullScreenButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_OVERFLOW:
                if (mOverflowButtonRight != null) {
                    mOverflowButtonRight.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_MUTE:
                if (mMuteButton != null) {
                    mMuteButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_ASPECT_RATIO:
                if (mAspectRationButton != null) {
                    mAspectRationButton.setVisibility(visibility);
                }
                break;
            case MediaControlView2.BUTTON_SETTINGS:
                if (mSettingsButton != null) {
                    mSettingsButton.setVisibility(visibility);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void requestPlayButtonFocus_impl() {
        if (mPlayPauseButton != null) {
            mPlayPauseButton.requestFocus();
        }
    }

    @Override
    public CharSequence getAccessibilityClassName_impl() {
        return MediaControlView2.class.getName();
    }

    @Override
    public boolean onTouchEvent_impl(MotionEvent ev) {
        return false;
    }

    // TODO: Should this function be removed?
    @Override
    public boolean onTrackballEvent_impl(MotionEvent ev) {
        return false;
    }

    @Override
    public void setEnabled_impl(boolean enabled) {
        super.setEnabled_impl(enabled);

        // TODO: Merge the below code with disableUnsupportedButtons().
        if (mPlayPauseButton != null) {
            mPlayPauseButton.setEnabled(enabled);
        }
        if (mFfwdButton != null) {
            mFfwdButton.setEnabled(enabled);
        }
        if (mRewButton != null) {
            mRewButton.setEnabled(enabled);
        }
        if (mNextButton != null) {
            mNextButton.setEnabled(enabled);
        }
        if (mPrevButton != null) {
            mPrevButton.setEnabled(enabled);
        }
        if (mProgress != null) {
            mProgress.setEnabled(enabled);
        }
        disableUnsupportedButtons();
    }

    @Override
    public void onVisibilityAggregated_impl(boolean isVisible) {
        super.onVisibilityAggregated_impl(isVisible);

        if (isVisible) {
            disableUnsupportedButtons();
            mInstance.removeCallbacks(mUpdateProgress);
            mInstance.post(mUpdateProgress);
        } else {
            mInstance.removeCallbacks(mUpdateProgress);
        }
    }

    public void setRouteSelector(MediaRouteSelector selector) {
        mRouteSelector = selector;
        if (mRouteSelector != null && !mRouteSelector.isEmpty()) {
            mRouteButton.setRouteSelector(selector, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
            mRouteButton.setVisibility(View.VISIBLE);
        } else {
            mRouteButton.setRouteSelector(MediaRouteSelector.EMPTY);
            mRouteButton.setVisibility(View.GONE);
        }
    }

    ///////////////////////////////////////////////////
    // Protected or private methods
    ///////////////////////////////////////////////////

    private boolean isPlaying() {
        if (mPlaybackState != null) {
            return mPlaybackState.getState() == PlaybackState.STATE_PLAYING;
        }
        return false;
    }

    private int getCurrentPosition() {
        mPlaybackState = mController.getPlaybackState();
        if (mPlaybackState != null) {
            return (int) mPlaybackState.getPosition();
        }
        return 0;
    }

    private int getBufferPercentage() {
        if (mDuration == 0) {
            return 0;
        }
        mPlaybackState = mController.getPlaybackState();
        if (mPlaybackState != null) {
            return (int) (mPlaybackState.getBufferedPosition() * 100) / mDuration;
        }
        return 0;
    }

    private boolean canPause() {
        if (mPlaybackState != null) {
            return (mPlaybackState.getActions() & PlaybackState.ACTION_PAUSE) != 0;
        }
        return true;
    }

    private boolean canSeekBackward() {
        if (mPlaybackState != null) {
            return (mPlaybackState.getActions() & PlaybackState.ACTION_REWIND) != 0;
        }
        return true;
    }

    private boolean canSeekForward() {
        if (mPlaybackState != null) {
            return (mPlaybackState.getActions() & PlaybackState.ACTION_FAST_FORWARD) != 0;
        }
        return true;
    }

    /**
     * Create the view that holds the widgets that control playback.
     * Derived classes can override this to create their own.
     *
     * @return The controller view.
     * @hide This doesn't work as advertised
     */
    protected View makeControllerView() {
        View root = ApiHelper.inflateLibLayout(mInstance.getContext(), R.layout.media_controller);
        initControllerView(root);
        return root;
    }

    private void initControllerView(View v) {
        mPlayDescription = mResources.getText(R.string.lockscreen_play_button_content_description);
        mPauseDescription =
                mResources.getText(R.string.lockscreen_pause_button_content_description);
        mReplayDescription =
                mResources.getText(R.string.lockscreen_replay_button_content_description);

        mRouteButton = v.findViewById(R.id.cast);

        mPlayPauseButton = v.findViewById(R.id.pause);
        if (mPlayPauseButton != null) {
            mPlayPauseButton.requestFocus();
            mPlayPauseButton.setOnClickListener(mPlayPauseListener);
            mPlayPauseButton.setColorFilter(R.color.gray);
            mPlayPauseButton.setEnabled(false);
        }
        mFfwdButton = v.findViewById(R.id.ffwd);
        if (mFfwdButton != null) {
            mFfwdButton.setOnClickListener(mFfwdListener);
            mFfwdButton.setColorFilter(R.color.gray);
            mFfwdButton.setEnabled(false);
        }
        mRewButton = v.findViewById(R.id.rew);
        if (mRewButton != null) {
            mRewButton.setOnClickListener(mRewListener);
            mRewButton.setColorFilter(R.color.gray);
            mRewButton.setEnabled(false);
        }
        mNextButton = v.findViewById(R.id.next);
        if (mNextButton != null) {
            mNextButton.setOnClickListener(mNextListener);
        }
        mPrevButton = v.findViewById(R.id.prev);
        if (mPrevButton != null) {
            mPrevButton.setOnClickListener(mPrevListener);
        }

        mBasicControls = v.findViewById(R.id.basic_controls);
        mSubtitleButton = v.findViewById(R.id.subtitle);
        if (mSubtitleButton != null) {
            mSubtitleButton.setOnClickListener(mSubtitleListener);
            mSubtitleButton.setColorFilter(R.color.gray);
            mSubtitleButton.setEnabled(false);
        }
        mFullScreenButton = v.findViewById(R.id.fullscreen);
        if (mFullScreenButton != null) {
            mFullScreenButton.setOnClickListener(mFullScreenListener);
            // TODO: Show Fullscreen button when only it is possible.
        }
        mOverflowButtonRight = v.findViewById(R.id.overflow_right);
        if (mOverflowButtonRight != null) {
            mOverflowButtonRight.setOnClickListener(mOverflowRightListener);
        }

        // TODO: should these buttons be shown as default?
        mExtraControls = v.findViewById(R.id.extra_controls);
        mCustomButtons = v.findViewById(R.id.custom_buttons);
        mOverflowButtonLeft = v.findViewById(R.id.overflow_left);
        if (mOverflowButtonLeft != null) {
            mOverflowButtonLeft.setOnClickListener(mOverflowLeftListener);
        }
        mMuteButton = v.findViewById(R.id.mute);
        mAspectRationButton = v.findViewById(R.id.aspect_ratio);
        mSettingsButton = v.findViewById(R.id.settings);
        if (mSettingsButton != null) {
            mSettingsButton.setOnClickListener(mSettingsButtonListener);
        }

        mProgress = v.findViewById(R.id.mediacontroller_progress);
        if (mProgress != null) {
            if (mProgress instanceof SeekBar) {
                SeekBar seeker = (SeekBar) mProgress;
                seeker.setOnSeekBarChangeListener(mSeekListener);
            }
            mProgress.setMax(MAX_PROGRESS);
        }

        mTitleView = v.findViewById(R.id.title_text);

        mEndTime = v.findViewById(R.id.time);
        mCurrentTime = v.findViewById(R.id.time_current);
        mFormatBuilder = new StringBuilder();
        mFormatter = new Formatter(mFormatBuilder, Locale.getDefault());

        mAdSkipView = v.findViewById(R.id.ad_skip_time);
        mAdRemainingView = v.findViewById(R.id.ad_remaining);
        mAdExternalLink = v.findViewById(R.id.ad_external_link);

        populateResourceIds();
        ListView settingsListView = (ListView) ApiHelper.inflateLibLayout(mInstance.getContext(),
                R.layout.settings_list);
        mSettingsAdapter = new SettingsAdapter(mSettingsMainTextIdsList, mSettingsSubTextIdsList,
                mSettingsIconIdsList, true);
        settingsListView.setAdapter(mSettingsAdapter);

        int width = mResources.getDimensionPixelSize(R.dimen.MediaControlView2_settings_width);
        mSettingsWindow = new PopupWindow(settingsListView, width,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // TODO: add listener to list view to allow each item to be selected.
    }

    /**
     * Disable pause or seek buttons if the stream cannot be paused or seeked.
     * This requires the control interface to be a MediaPlayerControlExt
     */
    private void disableUnsupportedButtons() {
        try {
            if (mPlayPauseButton != null && !canPause()) {
                mPlayPauseButton.setEnabled(false);
            }
            if (mRewButton != null && !canSeekBackward()) {
                mRewButton.setEnabled(false);
            }
            if (mFfwdButton != null && !canSeekForward()) {
                mFfwdButton.setEnabled(false);
            }
            // TODO What we really should do is add a canSeek to the MediaPlayerControl interface;
            // this scheme can break the case when applications want to allow seek through the
            // progress bar but disable forward/backward buttons.
            //
            // However, currently the flags SEEK_BACKWARD_AVAILABLE, SEEK_FORWARD_AVAILABLE,
            // and SEEK_AVAILABLE are all (un)set together; as such the aforementioned issue
            // shouldn't arise in existing applications.
            if (mProgress != null && !canSeekBackward() && !canSeekForward()) {
                mProgress.setEnabled(false);
            }
        } catch (IncompatibleClassChangeError ex) {
            // We were given an old version of the interface, that doesn't have
            // the canPause/canSeekXYZ methods. This is OK, it just means we
            // assume the media can be paused and seeked, and so we don't disable
            // the buttons.
        }
    }

    private final Runnable mUpdateProgress = new Runnable() {
        @Override
        public void run() {
            int pos = setProgress();
            boolean isShowing = mInstance.getVisibility() == View.VISIBLE;
            if (!mDragging && isShowing && isPlaying()) {
                mInstance.postDelayed(mUpdateProgress,
                        DEFAULT_PROGRESS_UPDATE_TIME_MS - (pos % DEFAULT_PROGRESS_UPDATE_TIME_MS));
            }
        }
    };

    private String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;

        mFormatBuilder.setLength(0);
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    private int setProgress() {
        if (mController == null || mDragging) {
            return 0;
        }
        int positionOnProgressBar = 0;
        int currentPosition = getCurrentPosition();
        if (mDuration > 0) {
            positionOnProgressBar = (int) (MAX_PROGRESS * (long) currentPosition / mDuration);
        }
        if (mProgress != null && currentPosition != mDuration) {
            mProgress.setProgress(positionOnProgressBar);
            mProgress.setSecondaryProgress(getBufferPercentage() * 10);
        }

        if (mEndTime != null) {
            mEndTime.setText(stringForTime(mDuration));

        }
        if (mCurrentTime != null) {
            mCurrentTime.setText(stringForTime(currentPosition));
        }

        if (mIsAdvertisement) {
            // Update the remaining number of seconds until the first 5 seconds of the
            // advertisement.
            if (mAdSkipView != null) {
                if (currentPosition <= AD_SKIP_WAIT_TIME_MS) {
                    if (mAdSkipView.getVisibility() == View.GONE) {
                        mAdSkipView.setVisibility(View.VISIBLE);
                    }
                    String skipTimeText = mResources.getString(
                            R.string.MediaControlView2_ad_skip_wait_time,
                            ((AD_SKIP_WAIT_TIME_MS - currentPosition) / 1000 + 1));
                    mAdSkipView.setText(skipTimeText);
                } else {
                    if (mAdSkipView.getVisibility() == View.VISIBLE) {
                        mAdSkipView.setVisibility(View.GONE);
                        mNextButton.setEnabled(true);
                        mNextButton.clearColorFilter();
                    }
                }
            }
            // Update the remaining number of seconds of the advertisement.
            if (mAdRemainingView != null) {
                int remainingTime =
                        (mDuration - currentPosition < 0) ? 0 : (mDuration - currentPosition);
                String remainingTimeText = mResources.getString(
                        R.string.MediaControlView2_ad_remaining_time,
                        stringForTime(remainingTime));
                mAdRemainingView.setText(remainingTimeText);
            }
        }
        return currentPosition;
    }

    private void togglePausePlayState() {
        if (isPlaying()) {
            mControls.pause();
            mPlayPauseButton.setImageDrawable(
                    mResources.getDrawable(R.drawable.ic_play_circle_filled, null));
            mPlayPauseButton.setContentDescription(mPlayDescription);
        } else {
            mControls.play();
            mPlayPauseButton.setImageDrawable(
                    mResources.getDrawable(R.drawable.ic_pause_circle_filled, null));
            mPlayPauseButton.setContentDescription(mPauseDescription);
        }
    }

    // There are two scenarios that can trigger the seekbar listener to trigger:
    //
    // The first is the user using the touchpad to adjust the posititon of the
    // seekbar's thumb. In this case onStartTrackingTouch is called followed by
    // a number of onProgressChanged notifications, concluded by onStopTrackingTouch.
    // We're setting the field "mDragging" to true for the duration of the dragging
    // session to avoid jumps in the position in case of ongoing playback.
    //
    // The second scenario involves the user operating the scroll ball, in this
    // case there WON'T BE onStartTrackingTouch/onStopTrackingTouch notifications,
    // we will simply apply the updated position without suspending regular updates.
    private final OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {
            if (!mSeekAvailable) {
                return;
            }

            mDragging = true;

            // By removing these pending progress messages we make sure
            // that a) we won't update the progress while the user adjusts
            // the seekbar and b) once the user is done dragging the thumb
            // we will post one of these messages to the queue again and
            // this ensures that there will be exactly one message queued up.
            mInstance.removeCallbacks(mUpdateProgress);

            // Check if playback is currently stopped. In this case, update the pause button to
            // show the play image instead of the replay image.
            if (mIsStopped) {
                mPlayPauseButton.setImageDrawable(
                        mResources.getDrawable(R.drawable.ic_play_circle_filled, null));
                mPlayPauseButton.setContentDescription(mPlayDescription);
                mIsStopped = false;
            }
        }

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (!mSeekAvailable) {
                return;
            }
            if (!fromUser) {
                // We're not interested in programmatically generated changes to
                // the progress bar's position.
                return;
            }
            if (mDuration > 0) {
                int newPosition = (int) (((long) mDuration * progress) / MAX_PROGRESS);
                mControls.seekTo(newPosition);

                if (mCurrentTime != null) {
                    mCurrentTime.setText(stringForTime(newPosition));
                }
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
            if (!mSeekAvailable) {
                return;
            }
            mDragging = false;

            setProgress();

            // Ensure that progress is properly updated in the future,
            // the call to show() does not guarantee this because it is a
            // no-op if we are already showing.
            mInstance.post(mUpdateProgress);
        }
    };

    private final View.OnClickListener mPlayPauseListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            togglePausePlayState();
        }
    };

    private final View.OnClickListener mRewListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = getCurrentPosition() - REWIND_TIME_MS;
            mControls.seekTo(pos);
            setProgress();
        }
    };

    private final View.OnClickListener mFfwdListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = getCurrentPosition() + FORWARD_TIME_MS;
            mControls.seekTo(pos);
            setProgress();
        }
    };

    private final View.OnClickListener mNextListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mControls.skipToNext();
        }
    };

    private final View.OnClickListener mPrevListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mControls.skipToPrevious();
        }
    };

    private final View.OnClickListener mSubtitleListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!mSubtitleIsEnabled) {
                mSubtitleButton.setImageDrawable(
                        mResources.getDrawable(R.drawable.ic_media_subtitle_enabled, null));
                mController.sendCommand(MediaControlView2.COMMAND_SHOW_SUBTITLE, null, null);
                mSubtitleIsEnabled = true;
            } else {
                mSubtitleButton.setImageDrawable(
                        mResources.getDrawable(R.drawable.ic_media_subtitle_disabled, null));
                mController.sendCommand(MediaControlView2.COMMAND_HIDE_SUBTITLE, null, null);
                mSubtitleIsEnabled = false;
            }
        }
    };

    private final View.OnClickListener mFullScreenListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final boolean isEnteringFullScreen = !mIsFullScreen;
            // TODO: Re-arrange the button layouts according to the UX.
            if (isEnteringFullScreen) {
                mFullScreenButton.setImageDrawable(
                        mResources.getDrawable(R.drawable.ic_fullscreen_exit, null));
            } else {
                mFullScreenButton.setImageDrawable(
                        mResources.getDrawable(R.drawable.ic_fullscreen, null));
            }
            Bundle args = new Bundle();
            args.putBoolean(ARGUMENT_KEY_FULLSCREEN, isEnteringFullScreen);
            mController.sendCommand(MediaControlView2.COMMAND_SET_FULLSCREEN, args, null);

            mIsFullScreen = isEnteringFullScreen;
        }
    };

    private final View.OnClickListener mOverflowRightListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mBasicControls.setVisibility(View.GONE);
            mExtraControls.setVisibility(View.VISIBLE);
        }
    };

    private final View.OnClickListener mOverflowLeftListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mBasicControls.setVisibility(View.VISIBLE);
            mExtraControls.setVisibility(View.GONE);
        }
    };

    private final View.OnClickListener mSettingsButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int itemHeight = mResources.getDimensionPixelSize(
                    R.dimen.MediaControlView2_settings_height);
            int totalHeight = mSettingsAdapter.getCount() * itemHeight;
            int margin = (-1) * mResources.getDimensionPixelSize(
                    R.dimen.MediaControlView2_settings_offset);
            mSettingsWindow.showAsDropDown(mInstance, margin, margin - totalHeight,
                    Gravity.BOTTOM | Gravity.RIGHT);
        }
    };

    // The title bar is made up of two separate LinearLayouts. If the sum of the two bars are
    // greater than the length of the title bar, reduce the size of the left bar (which makes the
    // TextView that contains the title of the media file shrink).
    private final View.OnLayoutChangeListener mTitleBarLayoutChangeListener
            = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            if (mRoot != null) {
                int titleBarWidth = mRoot.findViewById(R.id.title_bar).getWidth();

                View leftBar = mRoot.findViewById(R.id.title_bar_left);
                View rightBar = mRoot.findViewById(R.id.title_bar_right);
                int leftBarWidth = leftBar.getWidth();
                int rightBarWidth = rightBar.getWidth();

                RelativeLayout.LayoutParams params =
                        (RelativeLayout.LayoutParams) leftBar.getLayoutParams();
                if (leftBarWidth + rightBarWidth > titleBarWidth) {
                    params.width = titleBarWidth - rightBarWidth;
                    mPrevLeftBarWidth = leftBarWidth;
                } else if (leftBarWidth + rightBarWidth < titleBarWidth && mPrevLeftBarWidth != 0) {
                    params.width = mPrevLeftBarWidth;
                    mPrevLeftBarWidth = 0;
                }
                leftBar.setLayoutParams(params);
            }
        }
    };

    private void updateDuration() {
        if (mMetadata != null) {
            if (mMetadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                mDuration = (int) mMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                // update progress bar
                setProgress();
            }
        }
    }

    private void updateTitle() {
        if (mMetadata != null) {
            if (mMetadata.containsKey(MediaMetadata.METADATA_KEY_TITLE)) {
                mTitleView.setText(mMetadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            }
        }
    }

    private void updateLayout() {
        if (mIsAdvertisement) {
            mRewButton.setVisibility(View.GONE);
            mFfwdButton.setVisibility(View.GONE);
            mPrevButton.setVisibility(View.GONE);
            mCurrentTime.setVisibility(View.GONE);
            mEndTime.setVisibility(View.GONE);

            mAdSkipView.setVisibility(View.VISIBLE);
            mAdRemainingView.setVisibility(View.VISIBLE);
            mAdExternalLink.setVisibility(View.VISIBLE);

            mProgress.setEnabled(false);
            mNextButton.setEnabled(false);
            mNextButton.setColorFilter(R.color.gray);
        } else {
            mRewButton.setVisibility(View.VISIBLE);
            mFfwdButton.setVisibility(View.VISIBLE);
            mPrevButton.setVisibility(View.VISIBLE);
            mCurrentTime.setVisibility(View.VISIBLE);
            mEndTime.setVisibility(View.VISIBLE);

            mAdSkipView.setVisibility(View.GONE);
            mAdRemainingView.setVisibility(View.GONE);
            mAdExternalLink.setVisibility(View.GONE);

            mProgress.setEnabled(true);
            mNextButton.setEnabled(true);
            mNextButton.clearColorFilter();
            disableUnsupportedButtons();
        }
    }

    private void populateResourceIds() {
        // TODO: create record class for storing this info
        mSettingsMainTextIdsList = new ArrayList<Integer>();
        mSettingsMainTextIdsList.add(R.string.MediaControlView2_cc_text);
        mSettingsMainTextIdsList.add(R.string.MediaControlView2_audio_track_text);
        mSettingsMainTextIdsList.add(R.string.MediaControlView2_video_quality_text);
        mSettingsMainTextIdsList.add(R.string.MediaControlView2_playback_speed_text);
        mSettingsMainTextIdsList.add(R.string.MediaControlView2_help_text);

        // TODO: Update the following code to be dynamic.
        mSettingsSubTextIdsList = new ArrayList<Integer>();
        mSettingsSubTextIdsList.add(R.string.MediaControlView2_cc_text);
        mSettingsSubTextIdsList.add(R.string.MediaControlView2_audio_track_text);
        mSettingsSubTextIdsList.add(R.string.MediaControlView2_video_quality_text);
        mSettingsSubTextIdsList.add(R.string.MediaControlView2_playback_speed_text);
        mSettingsSubTextIdsList.add(RESOURCE_NON_EXISTENT);

        mSettingsIconIdsList = new ArrayList<Integer>();
        mSettingsIconIdsList.add(R.drawable.ic_closed_caption_off);
        mSettingsIconIdsList.add(R.drawable.ic_audiotrack);
        mSettingsIconIdsList.add(R.drawable.ic_high_quality);
        mSettingsIconIdsList.add(R.drawable.ic_play_circle_filled);
        mSettingsIconIdsList.add(R.drawable.ic_help);
    }

    private class MediaControllerCallback extends MediaController.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mPlaybackState = state;

            // Update pause button depending on playback state for the following two reasons:
            //   1) Need to handle case where app customizes playback state behavior when app
            //      activity is resumed.
            //   2) Need to handle case where the media file reaches end of duration.
            if (mPlaybackState.getState() != mPrevState) {
                switch (mPlaybackState.getState()) {
                    case PlaybackState.STATE_PLAYING:
                        mPlayPauseButton.setImageDrawable(
                                mResources.getDrawable(R.drawable.ic_pause_circle_filled, null));
                        mPlayPauseButton.setContentDescription(mPauseDescription);
                        mInstance.removeCallbacks(mUpdateProgress);
                        mInstance.post(mUpdateProgress);
                        break;
                    case PlaybackState.STATE_PAUSED:
                        mPlayPauseButton.setImageDrawable(
                                mResources.getDrawable(R.drawable.ic_play_circle_filled, null));
                        mPlayPauseButton.setContentDescription(mPlayDescription);
                        break;
                    case PlaybackState.STATE_STOPPED:
                        mPlayPauseButton.setImageDrawable(
                                mResources.getDrawable(R.drawable.ic_replay_circle_filled, null));
                        mPlayPauseButton.setContentDescription(mReplayDescription);
                        mIsStopped = true;
                        break;
                    default:
                        break;
                }
                mPrevState = mPlaybackState.getState();
            }

            if (mPlaybackActions != mPlaybackState.getActions()) {
                long newActions = mPlaybackState.getActions();
                if ((newActions & PlaybackState.ACTION_PAUSE) != 0) {
                    mPlayPauseButton.clearColorFilter();
                    mPlayPauseButton.setEnabled(true);
                }
                if ((newActions & PlaybackState.ACTION_REWIND) != 0) {
                    mRewButton.clearColorFilter();
                    mRewButton.setEnabled(true);
                }
                if ((newActions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
                    mFfwdButton.clearColorFilter();
                    mFfwdButton.setEnabled(true);
                }
                if ((newActions & PlaybackState.ACTION_SEEK_TO) != 0) {
                    mSeekAvailable = true;
                } else {
                    mSeekAvailable = false;
                }
                mPlaybackActions = newActions;
            }

            // Add buttons if custom actions are present.
            List<PlaybackState.CustomAction> customActions = mPlaybackState.getCustomActions();
            mCustomButtons.removeAllViews();
            if (customActions.size() > 0) {
                for (PlaybackState.CustomAction action : customActions) {
                    ImageButton button = new ImageButton(mInstance.getContext(),
                            null /* AttributeSet */, 0 /* Style */);
                    // TODO: Apply R.style.BottomBarButton to this button using library context.
                    // Refer Constructor with argument (int defStyleRes) of View.java
                    button.setImageResource(action.getIcon());
                    button.setTooltipText(action.getName());
                    final String actionString = action.getAction().toString();
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO: Currently, we are just sending extras that came from session.
                            // Is it the right behavior?
                            mControls.sendCustomAction(actionString, action.getExtras());
                            mInstance.setVisibility(View.VISIBLE);
                        }
                    });
                    mCustomButtons.addView(button);
                }
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mMetadata = metadata;
            updateDuration();
            updateTitle();
        }

        @Override
        public void onSessionEvent(String event, Bundle extras) {
            if (event.equals(EVENT_UPDATE_SUBTITLE_STATUS)) {
                boolean newSubtitleStatus = extras.getBoolean(KEY_STATE_CONTAINS_SUBTITLE);
                if (newSubtitleStatus != mContainsSubtitle) {
                    if (newSubtitleStatus) {
                        mSubtitleButton.clearColorFilter();
                        mSubtitleButton.setEnabled(true);
                    } else {
                        mSubtitleButton.setColorFilter(R.color.gray);
                        mSubtitleButton.setEnabled(false);
                    }
                    mContainsSubtitle = newSubtitleStatus;
                }
            } else if (event.equals(EVENT_UPDATE_MEDIA_TYPE_STATUS)) {
                boolean newStatus = extras.getBoolean(KEY_STATE_IS_ADVERTISEMENT);
                if (newStatus != mIsAdvertisement) {
                    mIsAdvertisement = newStatus;
                    updateLayout();
                }
            }
        }
    }

    private class SettingsAdapter extends BaseAdapter {
        List<Integer> mMainTextIds;
        List<Integer> mSubTextIds;
        List<Integer> mIconIds;
        boolean mIsCheckable;

        public SettingsAdapter(List<Integer> mainTextIds, @Nullable List<Integer> subTextIds,
                @Nullable List<Integer> iconIds, boolean isCheckable) {
            mMainTextIds = mainTextIds;
            mSubTextIds = subTextIds;
            mIconIds = iconIds;
            mIsCheckable = isCheckable;
        }

        @Override
        public int getCount() {
            return (mMainTextIds == null) ? 0 : mMainTextIds.size();
        }

        @Override
        public long getItemId(int position) {
            // Auto-generated method stub--does not have any purpose here
            // TODO: implement this.
            return 0;
        }

        @Override
        public Object getItem(int position) {
            // Auto-generated method stub--does not have any purpose here
            // TODO: implement this.
            return null;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup container) {
            View row = ApiHelper.inflateLibLayout(mInstance.getContext(),
                    R.layout.settings_list_item);
            TextView mainTextView = (TextView) row.findViewById(R.id.main_text);
            TextView subTextView = (TextView) row.findViewById(R.id.sub_text);
            ImageView iconView = (ImageView) row.findViewById(R.id.icon);
            ImageView checkView = (ImageView) row.findViewById(R.id.check);

            // Set main text
            mainTextView.setText(mResources.getString(mMainTextIds.get(position)));

            // Remove sub text and center the main text if sub texts do not exist at all or the sub
            // text at this particular position is set to RESOURCE_NON_EXISTENT.
            if (mSubTextIds == null || mSubTextIds.get(position) == RESOURCE_NON_EXISTENT) {
                subTextView.setVisibility(View.GONE);
            } else {
                // Otherwise, set sub text.
                subTextView.setText(mResources.getString(mSubTextIds.get(position)));
            }

            // Remove main icon and set visibility to gone if icons are set to null or the icon at
            // this particular position is set to RESOURCE_NON_EXISTENT.
            if (mIconIds == null || mIconIds.get(position) == RESOURCE_NON_EXISTENT) {
                iconView.setVisibility(View.GONE);
            } else {
                // Otherwise, set main icon.
                iconView.setImageDrawable(mResources.getDrawable(mIconIds.get(position), null));
            }

            // Set check icon
            // TODO: make the following code dynamic
            if (!mIsCheckable) {
                checkView.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
