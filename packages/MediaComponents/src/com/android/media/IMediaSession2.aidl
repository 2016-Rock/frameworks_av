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

import android.os.Bundle;
import android.os.ResultReceiver;
import android.net.Uri;

import com.android.media.IMediaSession2Callback;

/**
 * Interface to MediaSession2.
 * <p>
 * Keep this interface oneway. Otherwise a malicious app may implement fake version of this,
 * and holds calls from session to make session owner(s) frozen.
 */
oneway interface IMediaSession2 {
    // TODO(jaewan): add onCommand() to send private command
    // TODO(jaewan): Due to the nature of oneway calls, APIs can be called in out of order
    //               Add id for individual calls to address this.

    // TODO(jaewan): We may consider to add another binder just for the connection
    //               not to expose other methods to the controller whose connection wasn't accepted.
    //               But this would be enough for now because it's the same as existing
    //               MediaBrowser and MediaBrowserService.
    void connect(String callingPackage, IMediaSession2Callback callback);
    void release(IMediaSession2Callback caller);

    void setVolumeTo(IMediaSession2Callback caller, int value, int flags);
    void adjustVolume(IMediaSession2Callback caller, int direction, int flags);

    //////////////////////////////////////////////////////////////////////////////////////////////
    // send command
    //////////////////////////////////////////////////////////////////////////////////////////////
    void sendCommand(IMediaSession2Callback caller, in Bundle command, in Bundle args);
    void sendTransportControlCommand(IMediaSession2Callback caller,
            int commandCode, in Bundle args);
    void sendCustomCommand(IMediaSession2Callback caller, in Bundle command, in Bundle args,
            in ResultReceiver receiver);

    void prepareFromUri(IMediaSession2Callback caller, in Uri uri, in Bundle extra);
    void prepareFromSearch(IMediaSession2Callback caller, String query, in Bundle extra);
    void prepareFromMediaId(IMediaSession2Callback caller, String mediaId, in Bundle extra);
    void playFromUri(IMediaSession2Callback caller, in Uri uri, in Bundle extra);
    void playFromSearch(IMediaSession2Callback caller, String query, in Bundle extra);
    void playFromMediaId(IMediaSession2Callback caller, String mediaId, in Bundle extra);

   //////////////////////////////////////////////////////////////////////////////////////////////
    // Get library service specific
    //////////////////////////////////////////////////////////////////////////////////////////////
    void getBrowserRoot(IMediaSession2Callback callback, in Bundle rootHints);
}
