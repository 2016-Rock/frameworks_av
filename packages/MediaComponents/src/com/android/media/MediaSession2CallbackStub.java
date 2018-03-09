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

import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaItem2;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.PlaylistParams;
import android.media.PlaybackState2;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.media.MediaController2Impl.PlaybackInfoImpl;
import com.android.media.MediaSession2Impl.CommandButtonImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MediaSession2CallbackStub extends IMediaSession2Callback.Stub {
    private static final String TAG = "MS2CallbackStub";
    private static final boolean DEBUG = true; // TODO(jaewan): Change

    private final WeakReference<MediaController2Impl> mController;

    MediaSession2CallbackStub(MediaController2Impl controller) {
        mController = new WeakReference<>(controller);
    }

    private MediaController2Impl getController() throws IllegalStateException {
        final MediaController2Impl controller = mController.get();
        if (controller == null) {
            throw new IllegalStateException("Controller is released");
        }
        return controller;
    }

    private MediaBrowser2Impl getBrowser() throws IllegalStateException {
        final MediaController2Impl controller = getController();
        if (controller instanceof MediaBrowser2Impl) {
            return (MediaBrowser2Impl) controller;
        }
        return null;
    }

    public void destroy() {
        mController.clear();
    }

    @Override
    public void onPlaybackStateChanged(Bundle state) throws RuntimeException {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushPlaybackStateChanges(
                PlaybackState2.fromBundle(controller.getContext(), state));
    }

    @Override
    public void onPlaylistChanged(List<Bundle> playlist) throws RuntimeException {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (playlist == null) {
            return;
        }
        controller.pushPlaylistChanges(playlist);
    }

    @Override
    public void onPlaylistParamsChanged(Bundle params) throws RuntimeException {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushPlaylistParamsChanges(
                PlaylistParams.fromBundle(controller.getContext(), params));
    }

    @Override
    public void onPlaybackInfoChanged(Bundle playbackInfo) throws RuntimeException {
        if (DEBUG) {
            Log.d(TAG, "onPlaybackInfoChanged");
        }
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushPlaybackInfoChanges(
                PlaybackInfoImpl.fromBundle(controller.getContext(), playbackInfo));
    }

    @Override
    public void onConnected(IMediaSession2 sessionBinder, Bundle commandGroup,
            Bundle playbackState, Bundle playbackInfo, Bundle playlistParams, List<Bundle>
            itemBundleList, PendingIntent sessionActivity) {
        final MediaController2Impl controller = mController.get();
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "onConnected after MediaController2.close()");
            }
            return;
        }
        final Context context = controller.getContext();
        List<MediaItem2> itemList = null;
        if (itemBundleList != null) {
            itemList = new ArrayList<>();
            for (int i = 0; i < itemBundleList.size(); i++) {
                MediaItem2 item = MediaItem2.fromBundle(context, itemBundleList.get(i));
                if (item != null) {
                    itemList.add(item);
                }
            }
        }
        controller.onConnectedNotLocked(sessionBinder,
                CommandGroup.fromBundle(context, commandGroup),
                PlaybackState2.fromBundle(context, playbackState),
                PlaybackInfoImpl.fromBundle(context, playbackInfo),
                PlaylistParams.fromBundle(context, playlistParams),
                itemList, sessionActivity);
    }

    @Override
    public void onDisconnected() {
        final MediaController2Impl controller = mController.get();
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "onDisconnected after MediaController2.close()");
            }
            return;
        }
        controller.getInstance().close();
    }

    @Override
    public void onCustomLayoutChanged(List<Bundle> commandButtonlist) {
        if (commandButtonlist == null) {
            // Illegal call. Ignore
            return;
        }
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (controller == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        List<CommandButton> layout = new ArrayList<>();
        for (int i = 0; i < commandButtonlist.size(); i++) {
            CommandButton button = CommandButtonImpl.fromBundle(
                    controller.getContext(), commandButtonlist.get(i));
            if (button != null) {
                layout.add(button);
            }
        }
        controller.onCustomLayoutChanged(layout);
    }

    @Override
    public void sendCustomCommand(Bundle commandBundle, Bundle args, ResultReceiver receiver) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        Command command = Command.fromBundle(controller.getContext(), commandBundle);
        if (command == null) {
            return;
        }
        controller.onCustomCommand(command, args, receiver);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////
    // MediaBrowser specific
    ////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public void onGetLibraryRootDone(Bundle rootHints, String rootMediaId, Bundle rootExtra)
            throws RuntimeException {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onGetLibraryRootDone(rootHints, rootMediaId, rootExtra);
    }


    @Override
    public void onGetItemDone(String mediaId, Bundle itemBundle) throws RuntimeException {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onGetItemDone(mediaId,
                MediaItem2Impl.fromBundle(browser.getContext(), itemBundle));
    }

    @Override
    public void onGetChildrenDone(String parentId, int page, int pageSize,
            List<Bundle> itemBundleList, Bundle extras) throws RuntimeException {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }

        List<MediaItem2> result = null;
        if (itemBundleList != null) {
            result = new ArrayList<>();
            for (Bundle bundle : itemBundleList) {
                result.add(MediaItem2.fromBundle(browser.getContext(), bundle));
            }
        }
        browser.onGetChildrenDone(parentId, page, pageSize, result, extras);
    }

    @Override
    public void onSearchResultChanged(String query, int itemCount, Bundle extras)
            throws RuntimeException {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onSearchResultChanged(query, itemCount, extras);
    }

    @Override
    public void onGetSearchResultDone(String query, int page, int pageSize,
            List<Bundle> itemBundleList, Bundle extras) throws RuntimeException {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }

        List<MediaItem2> result = null;
        if (itemBundleList != null) {
            result = new ArrayList<>();
            for (Bundle bundle : itemBundleList) {
                result.add(MediaItem2.fromBundle(browser.getContext(), bundle));
            }
        }
        browser.onGetSearchResultDone(query, page, pageSize, result, extras);
    }

    @Override
    public void onChildrenChanged(String parentId, int itemCount, Bundle extras) {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onChildrenChanged(parentId, itemCount, extras);
    }
}
