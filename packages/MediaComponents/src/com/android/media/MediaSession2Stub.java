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
import android.media.MediaController2;
import android.media.MediaItem2;
import android.media.MediaLibraryService2.LibraryRoot;
import android.media.MediaSession2;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.PlaylistParams;
import android.media.PlaybackState2;
import android.media.Rating2;
import android.media.VolumeProvider2;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.support.annotation.GuardedBy;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.media.MediaLibraryService2Impl.MediaLibrarySessionImpl;
import com.android.media.MediaSession2Impl.CommandButtonImpl;
import com.android.media.MediaSession2Impl.ControllerInfoImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class MediaSession2Stub extends IMediaSession2.Stub {

    static final String ARGUMENT_KEY_POSITION = "android.media.media_session2.key_position";
    static final String ARGUMENT_KEY_ITEM_INDEX = "android.media.media_session2.key_item_index";
    static final String ARGUMENT_KEY_PLAYLIST_PARAMS =
            "android.media.media_session2.key_playlist_params";

    private static final String TAG = "MediaSession2Stub";
    private static final boolean DEBUG = true; // TODO(jaewan): Rename.

    private final Object mLock = new Object();
    private final WeakReference<MediaSession2Impl> mSession;

    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ControllerInfo> mControllers = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<ControllerInfo, Set<String>> mSubscriptions = new ArrayMap<>();

    public MediaSession2Stub(MediaSession2Impl session) {
        mSession = new WeakReference<>(session);
    }

    public void destroyNotLocked() {
        final List<ControllerInfo> list;
        synchronized (mLock) {
            mSession.clear();
            list = getControllers();
            mControllers.clear();
        }
        for (int i = 0; i < list.size(); i++) {
            IMediaSession2Callback controllerBinder =
                    ((ControllerInfoImpl) list.get(i).getProvider()).getControllerBinder();
            try {
                // Should be used without a lock hold to prevent potential deadlock.
                controllerBinder.onDisconnected();
            } catch (RemoteException e) {
                // Controller is gone. Should be fine because we're destroying.
            }
        }
    }

    private MediaSession2Impl getSession() throws IllegalStateException {
        final MediaSession2Impl session = mSession.get();
        if (session == null) {
            throw new IllegalStateException("Session is died");
        }
        return session;
    }

    private MediaLibrarySessionImpl getLibrarySession() throws IllegalStateException {
        final MediaSession2Impl session = getSession();
        if (!(session instanceof MediaLibrarySessionImpl)) {
            throw new RuntimeException("Session isn't a library session");
        }
        return (MediaLibrarySessionImpl) session;
    }

    private ControllerInfo getController(IMediaSession2Callback caller) {
        // TODO(jaewan): Find a way to return connection-in-progress-controller
        //               to be included here, because session owner may want to send some datas
        //               while onConnected() hasn't returned.
        synchronized (mLock) {
            return mControllers.get(caller.asBinder());
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // AIDL methods for session overrides
    //////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void connect(final IMediaSession2Callback caller, String callingPackage)
            throws RuntimeException {
        final MediaSession2Impl sessionImpl = getSession();
        final Context context = sessionImpl.getContext();
        final ControllerInfo controllerInfo = new ControllerInfo(context,
                Binder.getCallingUid(), Binder.getCallingPid(), callingPackage, caller);
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            CommandGroup allowedCommands = session.getCallback().onConnect(controllerInfo);
            // Don't reject connection for the controllerInfo from trusted app.
            // Otherwise server will fail to retrieve session's information to dispatch
            // media keys to.
            boolean accept = allowedCommands != null || controllerInfo.isTrusted();
            ControllerInfoImpl impl = ControllerInfoImpl.from(controllerInfo);
            if (accept) {
                if (DEBUG) {
                    Log.d(TAG, "Accepting connection, controllerInfo=" + controllerInfo
                            + " allowedCommands=" + allowedCommands);
                }
                synchronized (mLock) {
                    mControllers.put(impl.getId(), controllerInfo);
                }
                if (allowedCommands == null) {
                    // For trusted apps, send non-null allowed commands to keep connection.
                    allowedCommands = new CommandGroup(context);
                }
                // If connection is accepted, notify the current state to the controller.
                // It's needed because we cannot call synchronous calls between session/controller.
                // Note: We're doing this after the onConnectionChanged(), but there's no guarantee
                //       that events here are notified after the onConnected() because
                //       IMediaSession2Callback is oneway (i.e. async call) and CallbackStub will
                //       use thread poll for incoming calls.
                // TODO(jaewan): Should we protect getting playback state?
                final PlaybackState2 state = session.getInstance().getPlaybackState();
                final Bundle playbackStateBundle = (state != null) ? state.toBundle() : null;
                final Bundle playbackInfoBundle = ((MediaController2Impl.PlaybackInfoImpl)
                        session.getPlaybackInfo().getProvider()).toBundle();
                final PlaylistParams params = session.getInstance().getPlaylistParams();
                final Bundle paramsBundle = (params != null) ? params.toBundle() : null;
                final PendingIntent sessionActivity = session.getSessionActivity();
                final List<MediaItem2> playlist = session.getInstance().getPlaylist();
                final List<Bundle> playlistBundle = new ArrayList<>();
                if (playlist != null) {
                    // TODO(jaewan): Find a way to avoid concurrent modification exception.
                    for (int i = 0; i < playlist.size(); i++) {
                        final MediaItem2 item = playlist.get(i);
                        if (item != null) {
                            final Bundle itemBundle = item.toBundle();
                            if (itemBundle != null) {
                                playlistBundle.add(itemBundle);
                            }
                        }
                    }
                }

                // Double check if session is still there, because close() can be called in another
                // thread.
                if (mSession.get() == null) {
                    return;
                }
                try {
                    caller.onConnected(MediaSession2Stub.this,
                            allowedCommands.toBundle(), playbackStateBundle, playbackInfoBundle,
                            paramsBundle, playlistBundle, sessionActivity);
                } catch (RemoteException e) {
                    // Controller may be died prematurely.
                    // TODO(jaewan): Handle here.
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Rejecting connection, controllerInfo=" + controllerInfo);
                }
                try {
                    caller.onDisconnected();
                } catch (RemoteException e) {
                    // Controller may be died prematurely.
                    // Not an issue because we'll ignore it anyway.
                }
            }
        });
    }

    @Override
    public void release(IMediaSession2Callback caller) throws RemoteException {
        synchronized (mLock) {
            ControllerInfo controllerInfo = mControllers.remove(caller.asBinder());
            if (DEBUG) {
                Log.d(TAG, "releasing " + controllerInfo);
            }
            mSubscriptions.remove(controllerInfo);
        }
    }

    @Override
    public void setVolumeTo(IMediaSession2Callback caller, int value, int flags)
            throws RuntimeException {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            // TODO(jaewan): Sanity check.
            Command command = new Command(
                    session.getContext(), MediaSession2.COMMAND_CODE_SET_VOLUME);
            boolean accepted = session.getCallback().onCommandRequest(controller, command);
            if (!accepted) {
                // Don't run rejected command.
                if (DEBUG) {
                    Log.d(TAG, "Command " + MediaSession2.COMMAND_CODE_SET_VOLUME + " from "
                            + controller + " was rejected by " + session);
                }
                return;
            }

            VolumeProvider2 volumeProvider = session.getVolumeProvider();
            if (volumeProvider == null) {
                // TODO(jaewan): Set local stream volume
            } else {
                volumeProvider.onSetVolumeTo(value);
            }
        });
    }

    @Override
    public void adjustVolume(IMediaSession2Callback caller, int direction, int flags)
            throws RuntimeException {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            // TODO(jaewan): Sanity check.
            Command command = new Command(
                    session.getContext(), MediaSession2.COMMAND_CODE_SET_VOLUME);
            boolean accepted = session.getCallback().onCommandRequest(controller, command);
            if (!accepted) {
                // Don't run rejected command.
                if (DEBUG) {
                    Log.d(TAG, "Command " + MediaSession2.COMMAND_CODE_SET_VOLUME + " from "
                            + controller + " was rejected by " + session);
                }
                return;
            }

            VolumeProvider2 volumeProvider = session.getVolumeProvider();
            if (volumeProvider == null) {
                // TODO(jaewan): Adjust local stream volume
            } else {
                volumeProvider.onAdjustVolume(direction);
            }
        });
    }

    @Override
    public void sendCommand(IMediaSession2Callback caller, Bundle command, Bundle args)
            throws RuntimeException {
        // TODO(jaewan): Generic command
    }

    @Override
    public void sendTransportControlCommand(IMediaSession2Callback caller,
            int commandCode, Bundle args) throws RuntimeException {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            // TODO(jaewan): Sanity check.
            Command command = new Command(session.getContext(), commandCode);
            boolean accepted = session.getCallback().onCommandRequest(controller, command);
            if (!accepted) {
                // Don't run rejected command.
                if (DEBUG) {
                    Log.d(TAG, "Command " + commandCode + " from "
                            + controller + " was rejected by " + session);
                }
                return;
            }

            switch (commandCode) {
                case MediaSession2.COMMAND_CODE_PLAYBACK_PLAY:
                    session.getInstance().play();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_PAUSE:
                    session.getInstance().pause();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_STOP:
                    session.getInstance().stop();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM:
                    session.getInstance().skipToPrevious();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM:
                    session.getInstance().skipToNext();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_PREPARE:
                    session.getInstance().prepare();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_FAST_FORWARD:
                    session.getInstance().fastForward();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_REWIND:
                    session.getInstance().rewind();
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_SEEK_TO:
                    session.getInstance().seekTo(args.getLong(ARGUMENT_KEY_POSITION));
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_SET_CURRENT_PLAYLIST_ITEM:
                    // TODO(jaewan): Implement
                    /*
                    session.getInstance().skipToPlaylistItem(
                            args.getInt(ARGUMENT_KEY_ITEM_INDEX));
                    */
                    break;
                case MediaSession2.COMMAND_CODE_PLAYBACK_SET_PLAYLIST_PARAMS:
                    session.getInstance().setPlaylistParams(
                            PlaylistParams.fromBundle(session.getContext(),
                                    args.getBundle(ARGUMENT_KEY_PLAYLIST_PARAMS)));
                    break;
                default:
                    // TODO(jaewan): Resend unknown (new) commands through the custom command.
            }
        });
    }

    @Override
    public void sendCustomCommand(final IMediaSession2Callback caller, final Bundle commandBundle,
            final Bundle args, final ResultReceiver receiver) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            final Command command = Command.fromBundle(session.getContext(), commandBundle);
            session.getCallback().onCustomCommand(controller, command, args, receiver);
        });
    }

    @Override
    public void prepareFromUri(final IMediaSession2Callback caller, final Uri uri,
            final Bundle extras) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.getCallback().onPrepareFromUri(controller, uri, extras);
        });
    }

    @Override
    public void prepareFromSearch(final IMediaSession2Callback caller, final String query,
            final Bundle extras) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.getCallback().onPrepareFromSearch(controller, query, extras);
        });
    }

    @Override
    public void prepareFromMediaId(final IMediaSession2Callback caller, final String mediaId,
            final Bundle extras) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.getCallback().onPrepareFromMediaId(controller, mediaId, extras);
        });
    }

    @Override
    public void playFromUri(final IMediaSession2Callback caller, final Uri uri,
            final Bundle extras) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.getCallback().onPlayFromUri(controller, uri, extras);
        });
    }

    @Override
    public void playFromSearch(final IMediaSession2Callback caller, final String query,
            final Bundle extras) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.getCallback().onPlayFromSearch(controller, query, extras);
        });
    }

    @Override
    public void playFromMediaId(final IMediaSession2Callback caller, final String mediaId,
            final Bundle extras) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.getCallback().onPlayFromMediaId(controller, mediaId, extras);
        });
    }

    @Override
    public void setRating(final IMediaSession2Callback caller, final String mediaId,
            final Bundle ratingBundle) {
        final MediaSession2Impl sessionImpl = getSession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "Command from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            Rating2 rating = Rating2Impl.fromBundle(session.getContext(), ratingBundle);
            session.getCallback().onSetRating(controller, mediaId, rating);
        });
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // AIDL methods for LibrarySession overrides
    //////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void getBrowserRoot(IMediaSession2Callback caller, Bundle rootHints)
            throws RuntimeException {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "getBrowerRoot() from a controller that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            final ControllerInfoImpl controllerImpl = ControllerInfoImpl.from(controller);
            LibraryRoot root = session.getCallback().onGetLibraryRoot(controller, rootHints);
            try {
                controllerImpl.getControllerBinder().onGetLibraryRootDone(rootHints,
                        root == null ? null : root.getRootId(),
                        root == null ? null : root.getExtras());
            } catch (RemoteException e) {
                // Controller may be died prematurely.
                // TODO(jaewan): Handle this.
            }
        });
    }

    @Override
    public void getItem(IMediaSession2Callback caller, String mediaId) throws RuntimeException {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "getItem() from a controller that hasn't connected. Ignore");
            }
            return;
        }
        if (mediaId == null) {
            if (DEBUG) {
                Log.d(TAG, "mediaId shouldn't be null");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            final ControllerInfoImpl controllerImpl = ControllerInfoImpl.from(controller);
            MediaItem2 result = session.getCallback().onGetItem(controller, mediaId);
            try {
                controllerImpl.getControllerBinder().onGetItemDone(
                        mediaId, result == null ? null : result.toBundle());
            } catch (RemoteException e) {
                // Controller may be died prematurely.
                // TODO(jaewan): Handle this.
            }
        });
    }

    @Override
    public void getChildren(IMediaSession2Callback caller, String parentId, int page,
            int pageSize, Bundle extras) throws RuntimeException {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "getChildren() from a controller that hasn't connected. Ignore");
            }
            return;
        }
        if (parentId == null) {
            if (DEBUG) {
                Log.d(TAG, "parentId shouldn't be null");
            }
            return;
        }
        if (page < 1 || pageSize < 1) {
            if (DEBUG) {
                Log.d(TAG, "Neither page nor pageSize should be less than 1");
            }
            return;
        }

        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            final ControllerInfoImpl controllerImpl = ControllerInfoImpl.from(controller);
            List<MediaItem2> result = session.getCallback().onGetChildren(
                    controller, parentId, page, pageSize, extras);
            if (result != null && result.size() > pageSize) {
                throw new IllegalArgumentException("onGetChildren() shouldn't return media items "
                        + "more than pageSize. result.size()=" + result.size() + " pageSize="
                        + pageSize);
            }

            List<Bundle> bundleList = null;
            if (result != null) {
                bundleList = new ArrayList<>();
                for (MediaItem2 item : result) {
                    bundleList.add(item == null ? null : item.toBundle());
                }
            }

            try {
                controllerImpl.getControllerBinder().onGetChildrenDone(
                        parentId, page, pageSize, bundleList, extras);
            } catch (RemoteException e) {
                // Controller may be died prematurely.
                // TODO(jaewan): Handle this.
            }
        });
    }

    @Override
    public void search(IMediaSession2Callback caller, String query, Bundle extras) {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "search() from a controller that hasn't connected. Ignore");
            }
            return;
        }
        if (TextUtils.isEmpty(query)) {
            if (DEBUG) {
                Log.d(TAG, "query shouldn't be empty");
            }
            return;
        }

        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            final ControllerInfoImpl controllerImpl = ControllerInfoImpl.from(controller);
            session.getCallback().onSearch(controller, query, extras);
        });
    }

    @Override
    public void getSearchResult(IMediaSession2Callback caller, String query, int page,
            int pageSize, Bundle extras) {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "getSearchResult() from a controller that hasn't connected. Ignore");
            }
            return;
        }
        if (TextUtils.isEmpty(query)) {
            if (DEBUG) {
                Log.d(TAG, "query shouldn't be empty");
            }
            return;
        }
        if (page < 1 || pageSize < 1) {
            if (DEBUG) {
                Log.d(TAG, "Neither page nor pageSize should be less than 1");
            }
            return;
        }

        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            final ControllerInfoImpl controllerImpl = ControllerInfoImpl.from(controller);
            List<MediaItem2> result = session.getCallback().onGetSearchResult(
                    controller, query, page, pageSize, extras);
            if (result != null && result.size() > pageSize) {
                throw new IllegalArgumentException("onGetSearchResult() shouldn't return media "
                        + "items more than pageSize. result.size()=" + result.size() + " pageSize="
                        + pageSize);
            }

            List<Bundle> bundleList = null;
            if (result != null) {
                bundleList = new ArrayList<>();
                for (MediaItem2 item : result) {
                    bundleList.add(item == null ? null : item.toBundle());
                }
            }

            try {
                controllerImpl.getControllerBinder().onGetSearchResultDone(
                        query, page, pageSize, bundleList, extras);
            } catch (RemoteException e) {
                // Controller may be died prematurely.
                // TODO(jaewan): Handle this.
            }
        });
    }

    @Override
    public void subscribe(final IMediaSession2Callback caller, final String parentId,
            final Bundle option) {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "subscribe() from a browser that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            session.getCallback().onSubscribe(controller, parentId, option);
            synchronized (mLock) {
                Set<String> subscription = mSubscriptions.get(controller);
                if (subscription == null) {
                    subscription = new HashSet<>();
                    mSubscriptions.put(controller, subscription);
                }
                subscription.add(parentId);
            }
        });
    }

    @Override
    public void unsubscribe(final IMediaSession2Callback caller, final String parentId) {
        final MediaLibrarySessionImpl sessionImpl = getLibrarySession();
        final ControllerInfo controller = getController(caller);
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "unsubscribe() from a browser that hasn't connected. Ignore");
            }
            return;
        }
        sessionImpl.getCallbackExecutor().execute(() -> {
            final MediaLibrarySessionImpl session = getLibrarySession();
            if (session == null) {
                return;
            }
            session.getCallback().onUnsubscribe(controller, parentId);
            synchronized (mLock) {
                mSubscriptions.remove(controller);
            }
        });
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // APIs for MediaSession2Impl
    //////////////////////////////////////////////////////////////////////////////////////////////

    // TODO(jaewan): Need a way to get controller with permissions
    public List<ControllerInfo> getControllers() {
        ArrayList<ControllerInfo> controllers = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mControllers.size(); i++) {
                controllers.add(mControllers.valueAt(i));
            }
        }
        return controllers;
    }

    // Should be used without a lock to prevent potential deadlock.
    public void notifyPlaybackStateChangedNotLocked(PlaybackState2 state) {
        final List<ControllerInfo> list = getControllers();
        for (int i = 0; i < list.size(); i++) {
            IMediaSession2Callback controllerBinder =
                    ControllerInfoImpl.from(list.get(i)).getControllerBinder();
            try {
                final Bundle bundle = state != null ? state.toBundle() : null;
                controllerBinder.onPlaybackStateChanged(bundle);
            } catch (RemoteException e) {
                Log.w(TAG, "Controller is gone", e);
                // TODO(jaewan): What to do when the controller is gone?
            }
        }
    }

    public void notifyCustomLayoutNotLocked(ControllerInfo controller, List<CommandButton> layout) {
        // TODO(jaewan): It's OK to be called while it's connecting, but not OK if the connection
        //               is rejected. Handle the case.
        IMediaSession2Callback controllerBinder =
                ControllerInfoImpl.from(controller).getControllerBinder();
        try {
            List<Bundle> layoutBundles = new ArrayList<>();
            for (int i = 0; i < layout.size(); i++) {
                Bundle bundle = ((CommandButtonImpl) layout.get(i).getProvider()).toBundle();
                if (bundle != null) {
                    layoutBundles.add(bundle);
                }
            }
            controllerBinder.onCustomLayoutChanged(layoutBundles);
        } catch (RemoteException e) {
            Log.w(TAG, "Controller is gone", e);
            // TODO(jaewan): What to do when the controller is gone?
        }
    }

    public void notifyPlaylistChanged(List<MediaItem2> playlist) {
        if (playlist == null) {
            return;
        }
        final List<Bundle> bundleList = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i) != null) {
                Bundle bundle = playlist.get(i).toBundle();
                if (bundle != null) {
                    bundleList.add(bundle);
                }
            }
        }
        final List<ControllerInfo> list = getControllers();
        for (int i = 0; i < list.size(); i++) {
            IMediaSession2Callback controllerBinder =
                    ControllerInfoImpl.from(list.get(i)).getControllerBinder();
            try {
                controllerBinder.onPlaylistChanged(bundleList);
            } catch (RemoteException e) {
                Log.w(TAG, "Controller is gone", e);
                // TODO(jaewan): What to do when the controller is gone?
            }
        }
    }

    public void notifyPlaylistParamsChanged(MediaSession2.PlaylistParams params) {
        final List<ControllerInfo> list = getControllers();
        for (int i = 0; i < list.size(); i++) {
            IMediaSession2Callback controllerBinder =
                    ControllerInfoImpl.from(list.get(i)).getControllerBinder();
            try {
                controllerBinder.onPlaylistParamsChanged(params.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Controller is gone", e);
                // TODO(jaewan): What to do when the controller is gone?
            }
        }
    }

    public void notifyPlaybackInfoChanged(MediaController2.PlaybackInfo playbackInfo) {
        final List<ControllerInfo> list = getControllers();
        for (int i = 0; i < list.size(); i++) {
            IMediaSession2Callback controllerBinder =
                    ControllerInfoImpl.from(list.get(i)).getControllerBinder();
            try {
                controllerBinder.onPlaybackInfoChanged(((MediaController2Impl.PlaybackInfoImpl)
                        playbackInfo.getProvider()).toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Controller is gone", e);
                // TODO(jaewan): What to do when the controller is gone?
            }
        }
    }

    public void sendCustomCommand(ControllerInfo controller, Command command, Bundle args,
            ResultReceiver receiver) {
        if (receiver != null && controller == null) {
            throw new IllegalArgumentException("Controller shouldn't be null if result receiver is"
                    + " specified");
        }
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        final IMediaSession2Callback controllerBinder =
                ControllerInfoImpl.from(controller).getControllerBinder();
        if (getController(controllerBinder) == null) {
            throw new IllegalArgumentException("Controller is gone");
        }
        sendCustomCommandInternal(controller, command, args, receiver);
    }

    public void sendCustomCommand(Command command, Bundle args) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        final List<ControllerInfo> controllers = getControllers();
        for (int i = 0; i < controllers.size(); i++) {
            sendCustomCommand(controllers.get(i), command, args, null);
        }
    }

    private void sendCustomCommandInternal(ControllerInfo controller, Command command, Bundle args,
            ResultReceiver receiver) {
        final IMediaSession2Callback controllerBinder =
                ControllerInfoImpl.from(controller).getControllerBinder();
        try {
            Bundle commandBundle = command.toBundle();
            controllerBinder.sendCustomCommand(commandBundle, args, receiver);
        } catch (RemoteException e) {
            Log.w(TAG, "Controller is gone", e);
            // TODO(jaewan): What to do when the controller is gone?
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // APIs for MediaLibrarySessionImpl
    //////////////////////////////////////////////////////////////////////////////////////////////

    public void notifySearchResultChanged(ControllerInfo controller, String query, int itemCount,
            Bundle extras) {
        final IMediaSession2Callback callbackBinder =
                ControllerInfoImpl.from(controller).getControllerBinder();
        try {
            callbackBinder.onSearchResultChanged(query, itemCount, extras);
        } catch (RemoteException e) {
            Log.w(TAG, "Controller is gone", e);
            // TODO(jaewan): What to do when the controller is gone?
        }
    }

    public void notifyChildrenChangedNotLocked(ControllerInfo controller, String parentId,
            int itemCount, Bundle extras) {
        // TODO(jaewan): Handle when controller is disconnected and no longer valid.
        //               Note: Commands may be sent while onConnected() is running. Should we also
        //                     consider it as error?
        notifyChildrenChangedInternalNotLocked(controller, parentId, itemCount, extras);
    }

    public void notifyChildrenChangedNotLocked(String parentId, int itemCount, Bundle extras) {
        final List<ControllerInfo> controllers = getControllers();
        for (int i = 0; i < controllers.size(); i++) {
            notifyChildrenChangedInternalNotLocked(controllers.get(i), parentId, itemCount,
                    extras);
        }
    }

    public void notifyChildrenChangedInternalNotLocked(final ControllerInfo controller,
            String parentId, int itemCount, Bundle extras) {
        // Ensure subscription
        synchronized (mLock) {
            Set<String> subscriptions = mSubscriptions.get(controller);
            if (subscriptions == null || !subscriptions.contains(parentId)) {
                return;
            }
        }
        final IMediaSession2Callback callbackBinder =
                ControllerInfoImpl.from(controller).getControllerBinder();
        try {
            callbackBinder.onChildrenChanged(parentId, itemCount, extras);
        } catch (RemoteException e) {
            // TODO(jaewan): Handle controller removed?
        }
    }
}
