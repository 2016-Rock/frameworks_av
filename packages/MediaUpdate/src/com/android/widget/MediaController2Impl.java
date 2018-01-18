/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.graphics.Canvas;
import android.media.session.MediaController;
import android.media.update.MediaController2Provider;
import android.media.update.ViewProvider;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.MediaController2;

public class MediaController2Impl implements MediaController2Provider {
    private final MediaController2 mInstance;
    private final ViewProvider mSuperProvider;

    public MediaController2Impl(MediaController2 instance, ViewProvider superProvider) {
        mInstance = instance;
        mSuperProvider = superProvider;

        // TODO: Implement
    }

    @Override
    public void setController_impl(MediaController controller) {
        // TODO: Implement
    }

    @Override
    public void setAnchorView_impl(View view) {
        // TODO: Implement
    }

    @Override
    public void show_impl() {
        // TODO: Implement
    }

    @Override
    public void show_impl(int timeout) {
        // TODO: Implement
    }

    @Override
    public boolean isShowing_impl() {
        // TODO: Implement
        return false;
    }

    @Override
    public void hide_impl() {
        // TODO: Implement
    }

    @Override
    public void setPrevNextListeners_impl(OnClickListener next, OnClickListener prev) {
        // TODO: Implement
    }

    @Override
    public void showCCButton_impl() {
        // TODO: Implement
    }

    @Override
    public boolean isPlaying_impl() {
        // TODO: Implement
        return false;
    }

    @Override
    public int getCurrentPosition_impl() {
        // TODO: Implement
        return 0;
    }

    @Override
    public int getBufferPercentage_impl() {
        // TODO: Implement
        return 0;
    }

    @Override
    public boolean canPause_impl() {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean canSeekBackward_impl() {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean canSeekForward_impl() {
        // TODO: Implement
        return false;
    }

    @Override
    public void showSubtitle_impl() {
        // TODO: Implement
    }

    @Override
    public void hideSubtitle_impl() {
        // TODO: Implement
    }

    @Override
    public void onAttachedToWindow_impl() {
        mSuperProvider.onAttachedToWindow_impl();
        // TODO: Implement
    }

    @Override
    public void onDetachedFromWindow_impl() {
        mSuperProvider.onDetachedFromWindow_impl();
        // TODO: Implement
    }

    @Override
    public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
        mSuperProvider.onLayout_impl(changed, left, top, right, bottom);
        // TODO: Implement
    }

    @Override
    public void draw_impl(Canvas canvas) {
        mSuperProvider.draw_impl(canvas);
        // TODO: Implement
    }

    @Override
    public CharSequence getAccessibilityClassName_impl() {
        // TODO: Implement
        return MediaController2.class.getName();
    }

    @Override
    public boolean onTouchEvent_impl(MotionEvent ev) {
        // TODO: Implement
        return mSuperProvider.onTouchEvent_impl(ev);
    }

    @Override
    public boolean onTrackballEvent_impl(MotionEvent ev) {
        // TODO: Implement
        return mSuperProvider.onTrackballEvent_impl(ev);
    }

    @Override
    public boolean onKeyDown_impl(int keyCode, KeyEvent event) {
        // TODO: Implement
        return mSuperProvider.onKeyDown_impl(keyCode, event);
    }

    @Override
    public void onFinishInflate_impl() {
        mSuperProvider.onFinishInflate_impl();
        // TODO: Implement
    }

    @Override
    public boolean dispatchKeyEvent_impl(KeyEvent event) {
        // TODO: Implement
        return mSuperProvider.dispatchKeyEvent_impl(event);
    }

    @Override
    public void setEnabled_impl(boolean enabled) {
        mSuperProvider.setEnabled_impl(enabled);
        // TODO: Implement
    }
}
