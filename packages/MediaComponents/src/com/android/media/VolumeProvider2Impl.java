/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.media;

import android.content.Context;
import android.media.VolumeProvider2;
import android.media.update.VolumeProvider2Provider;

public class VolumeProvider2Impl implements VolumeProvider2Provider {

    private final Context mContext;
    private final VolumeProvider2 mInstance;
    private final int mControlType;
    private final int mMaxVolume;

    private int mCurrentVolume;
    private Callback mCallback;

    public VolumeProvider2Impl(Context context, VolumeProvider2 instance,
            @VolumeProvider2.ControlType int controlType, int maxVolume, int currentVolume) {
        mContext = context;
        mInstance = instance;
        mControlType = controlType;
        mMaxVolume = maxVolume;
        mCurrentVolume = currentVolume;
    }

    @Override
    public int getControlType_impl() {
        return mControlType;
    }

    @Override
    public int getMaxVolume_impl() {
        return mMaxVolume;
    }

    @Override
    public int getCurrentVolume_impl() {
        return mCurrentVolume;
    }

    @Override
    public void setCurrentVolume_impl(int currentVolume) {
        mCurrentVolume = currentVolume;
        if (mCallback != null) {
            mCallback.onVolumeChanged(mInstance);
        }
    }

    /**
     * Sets a callback to receive volume changes.
     */
    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * Listens for changes to the volume.
     */
    public static abstract class Callback {
        public abstract void onVolumeChanged(VolumeProvider2 volumeProvider);
    }
}
