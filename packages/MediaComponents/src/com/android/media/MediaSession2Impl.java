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

import static android.media.MediaSession2.COMMAND_CODE_CUSTOM;
import static android.media.SessionToken2.TYPE_LIBRARY_SERVICE;
import static android.media.SessionToken2.TYPE_SESSION;
import static android.media.SessionToken2.TYPE_SESSION_SERVICE;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaController2;
import android.media.MediaController2.PlaybackInfo;
import android.media.MediaItem2;
import android.media.MediaLibraryService2;
import android.media.MediaMetadata2;
import android.media.MediaPlayerBase;
import android.media.MediaPlayerBase.EventCallback;
import android.media.MediaSession2;
import android.media.MediaSession2.Builder;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.PlaylistParams;
import android.media.MediaSession2.PlaylistParams.RepeatMode;
import android.media.MediaSession2.PlaylistParams.ShuffleMode;
import android.media.MediaSession2.SessionCallback;
import android.media.MediaSessionService2;
import android.media.PlaybackState2;
import android.media.SessionToken2;
import android.media.VolumeProvider2;
import android.media.session.MediaSessionManager;
import android.media.update.MediaSession2Provider;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Process;
import android.os.ResultReceiver;
import android.support.annotation.GuardedBy;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class MediaSession2Impl implements MediaSession2Provider {
    private static final String TAG = "MediaSession2";
    private static final boolean DEBUG = true;//Log.isLoggable(TAG, Log.DEBUG);

    private final Object mLock = new Object();

    private final MediaSession2 mInstance;
    private final Context mContext;
    private final String mId;
    private final Executor mCallbackExecutor;
    private final SessionCallback mCallback;
    private final MediaSession2Stub mSessionStub;
    private final SessionToken2 mSessionToken;
    private final AudioManager mAudioManager;
    private final ArrayMap<EventCallback, Executor> mCallbacks = new ArrayMap<>();
    private final PendingIntent mSessionActivity;

    // mPlayer is set to null when the session is closed, and we shouldn't throw an exception
    // nor leave log always for using mPlayer when it's null. Here's the reason.
    // When a MediaSession2 is closed, there could be a pended operation in the session callback
    // executor that may want to access the player. Here's the sample code snippet for that.
    //
    //   public void onFoo() {
    //     if (mPlayer == null) return; // first check
    //     mSessionCallbackExecutor.executor(() -> {
    //       // Error. Session may be closed and mPlayer can be null here.
    //       mPlayer.foo();
    //     });
    //   }
    //
    // By adding protective code, we can also protect APIs from being called after the close()
    //
    // TODO(jaewan): Should we put volatile here?
    @GuardedBy("mLock")
    private MediaPlayerBase mPlayer;
    @GuardedBy("mLock")
    private VolumeProvider2 mVolumeProvider;
    @GuardedBy("mLock")
    private PlaybackInfo mPlaybackInfo;
    @GuardedBy("mLock")
    private MyEventCallback mEventCallback;

    /**
     * Can be only called by the {@link Builder#build()}.
     *
     * @param context
     * @param player
     * @param id
     * @param volumeProvider
     * @param sessionActivity
     * @param callbackExecutor
     * @param callback
     */
    public MediaSession2Impl(Context context, MediaPlayerBase player, String id,
            VolumeProvider2 volumeProvider, PendingIntent sessionActivity,
            Executor callbackExecutor, SessionCallback callback) {
        // TODO(jaewan): Keep other params.
        mInstance = createInstance();

        // Argument checks are done by builder already.
        // Initialize finals first.
        mContext = context;
        mId = id;
        mCallback = callback;
        mCallbackExecutor = callbackExecutor;
        mSessionActivity = sessionActivity;
        mSessionStub = new MediaSession2Stub(this);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Infer type from the id and package name.
        String libraryService = getServiceName(context, MediaLibraryService2.SERVICE_INTERFACE, id);
        String sessionService = getServiceName(context, MediaSessionService2.SERVICE_INTERFACE, id);
        if (sessionService != null && libraryService != null) {
            throw new IllegalArgumentException("Ambiguous session type. Multiple"
                    + " session services define the same id=" + id);
        } else if (libraryService != null) {
            mSessionToken = new SessionToken2Impl(context, Process.myUid(), TYPE_LIBRARY_SERVICE,
                    mContext.getPackageName(), libraryService, id, null).getInstance();
        } else if (sessionService != null) {
            mSessionToken = new SessionToken2Impl(context, Process.myUid(), TYPE_SESSION_SERVICE,
                    mContext.getPackageName(), sessionService, id, null).getInstance();
        } else {
            mSessionToken = new SessionToken2Impl(context, Process.myUid(), TYPE_SESSION,
                    mContext.getPackageName(), null, id, mSessionStub).getInstance();
        }

        setPlayer(player, volumeProvider);

        // Ask server for the sanity check, and starts
        // Sanity check for making session ID unique 'per package' cannot be done in here.
        // Server can only know if the package has another process and has another session with the
        // same id. Note that 'ID is unique per package' is important for controller to distinguish
        // a session in another package.
        MediaSessionManager manager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (!manager.createSession2(mSessionToken)) {
            throw new IllegalStateException("Session with the same id is already used by"
                    + " another process. Use MediaController2 instead.");
        }
    }

    MediaSession2 createInstance() {
        return new MediaSession2(this);
    }

    private static String getServiceName(Context context, String serviceAction, String id) {
        PackageManager manager = context.getPackageManager();
        Intent serviceIntent = new Intent(serviceAction);
        serviceIntent.setPackage(context.getPackageName());
        List<ResolveInfo> services = manager.queryIntentServices(serviceIntent,
                PackageManager.GET_META_DATA);
        String serviceName = null;
        if (services != null) {
            for (int i = 0; i < services.size(); i++) {
                String serviceId = SessionToken2Impl.getSessionId(services.get(i));
                if (serviceId != null && TextUtils.equals(id, serviceId)) {
                    if (services.get(i).serviceInfo == null) {
                        continue;
                    }
                    if (serviceName != null) {
                        throw new IllegalArgumentException("Ambiguous session type. Multiple"
                                + " session services define the same id=" + id);
                    }
                    serviceName = services.get(i).serviceInfo.name;
                }
            }
        }
        return serviceName;
    }

    @Override
    public void setPlayer_impl(MediaPlayerBase player) {
        ensureCallingThread();
        if (player == null) {
            throw new IllegalArgumentException("player shouldn't be null");
        }
        setPlayer(player, null);
    }

    @Override
    public void setPlayer_impl(MediaPlayerBase player, VolumeProvider2 volumeProvider)
            throws IllegalArgumentException {
        ensureCallingThread();
        if (player == null) {
            throw new IllegalArgumentException("player shouldn't be null");
        }
        if (volumeProvider == null) {
            throw new IllegalArgumentException("volumeProvider shouldn't be null");
        }
        setPlayer(player, volumeProvider);
    }

    private void setPlayer(MediaPlayerBase player, VolumeProvider2 volumeProvider) {
        final PlaybackInfo info = createPlaybackInfo(volumeProvider, player.getAudioAttributes());
        synchronized (mLock) {
            if (mPlayer != null && mEventCallback != null) {
                // This might not work for a poorly implemented player.
                mPlayer.unregisterEventCallback(mEventCallback);
            }
            mPlayer = player;
            mEventCallback = new MyEventCallback(this, player);
            player.registerEventCallback(mCallbackExecutor, mEventCallback);
            mVolumeProvider = volumeProvider;
            mPlaybackInfo = info;
        }
        mSessionStub.notifyPlaybackInfoChanged(info);
        notifyPlaybackStateChangedNotLocked(mInstance.getPlaybackState());
    }

    private PlaybackInfo createPlaybackInfo(VolumeProvider2 volumeProvider, AudioAttributes attrs) {
        PlaybackInfo info;
        if (volumeProvider == null) {
            int stream;
            if (attrs == null) {
                stream = AudioManager.STREAM_MUSIC;
            } else {
                stream = attrs.getVolumeControlStream();
                if (stream == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                    // It may happen if the AudioAttributes doesn't have usage.
                    // Change it to the STREAM_MUSIC because it's not supported by audio manager
                    // for querying volume level.
                    stream = AudioManager.STREAM_MUSIC;
                }
            }
            info = MediaController2Impl.PlaybackInfoImpl.createPlaybackInfo(
                    mContext,
                    PlaybackInfo.PLAYBACK_TYPE_LOCAL,
                    attrs,
                    mAudioManager.isVolumeFixed()
                            ? VolumeProvider2.VOLUME_CONTROL_FIXED
                            : VolumeProvider2.VOLUME_CONTROL_ABSOLUTE,
                    mAudioManager.getStreamMaxVolume(stream),
                    mAudioManager.getStreamVolume(stream));
        } else {
            info = MediaController2Impl.PlaybackInfoImpl.createPlaybackInfo(
                    mContext,
                    PlaybackInfo.PLAYBACK_TYPE_REMOTE /* ControlType */,
                    attrs,
                    volumeProvider.getControlType(),
                    volumeProvider.getMaxVolume(),
                    volumeProvider.getCurrentVolume());
        }
        return info;
    }

    @Override
    public void close_impl() {
        // Stop system service from listening this session first.
        MediaSessionManager manager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        manager.destroySession2(mSessionToken);

        if (mSessionStub != null) {
            if (DEBUG) {
                Log.d(TAG, "session is now unavailable, id=" + mId);
            }
            // Invalidate previously published session stub.
            mSessionStub.destroyNotLocked();
        }
        synchronized (mLock) {
            if (mPlayer != null) {
                // close can be called multiple times
                mPlayer.unregisterEventCallback(mEventCallback);
                mPlayer = null;
            }
        }
    }

    @Override
    public MediaPlayerBase getPlayer_impl() {
        return getPlayer();
    }

    // TODO(jaewan): Change this to @NonNull
    @Override
    public SessionToken2 getToken_impl() {
        return mSessionToken;
    }

    @Override
    public List<ControllerInfo> getConnectedControllers_impl() {
        return mSessionStub.getControllers();
    }

    @Override
    public void setAudioFocusRequest_impl(int focusGain) {
        // implement
    }

    @Override
    public void play_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.play();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void pause_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.pause();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void stop_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.stop();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void skipToPrevious_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.skipToPrevious();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void skipToNext_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.skipToNext();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void setCustomLayout_impl(ControllerInfo controller, List<CommandButton> layout) {
        ensureCallingThread();
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (layout == null) {
            throw new IllegalArgumentException("layout shouldn't be null");
        }
        mSessionStub.notifyCustomLayoutNotLocked(controller, layout);
    }

    @Override
    public void setPlaylistParams_impl(PlaylistParams params) {
        if (params == null) {
            throw new IllegalArgumentException("params shouldn't be null");
        }
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.setPlaylistParams(params);
            mSessionStub.notifyPlaylistParamsChanged(params);
        }
    }

    @Override
    public PlaylistParams getPlaylistParams_impl() {
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            // TODO(jaewan): Is it safe to be called on any thread?
            //               Otherwise MediaSession2 should cache parameter of setPlaylistParams.
            return player.getPlaylistParams();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return null;
    }

    //////////////////////////////////////////////////////////////////////////////////////
    // TODO(jaewan): Implement follows
    //////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void setAllowedCommands_impl(ControllerInfo controller, CommandGroup commands) {
        // TODO(jaewan): Implement
    }

    @Override
    public void sendCustomCommand_impl(ControllerInfo controller, Command command, Bundle args,
            ResultReceiver receiver) {
        mSessionStub.sendCustomCommand(controller, command, args, receiver);
    }

    @Override
    public void sendCustomCommand_impl(Command command, Bundle args) {
        mSessionStub.sendCustomCommand(command, args);
    }

    @Override
    public void setPlaylist_impl(List<MediaItem2> playlist) {
        if (playlist == null) {
            throw new IllegalArgumentException("playlist shouldn't be null");
        }
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.setPlaylist(playlist);
            mSessionStub.notifyPlaylistChanged(playlist);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void addPlaylistItem_impl(int index, MediaItem2 item) {
        // TODO(jaewan): Implement
    }

    @Override
    public void removePlaylistItem_impl(MediaItem2 item) {
        // TODO(jaewan): Implement
    }

    @Override
    public void editPlaylistItem_impl(MediaItem2 item) {
        // TODO(jaewan): Implement
    }

    @Override
    public List<MediaItem2> getPlaylist_impl() {
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            // TODO(jaewan): Is it safe to be called on any thread?
            //               Otherwise MediaSession2 should cache parameter of setPlaylist.
            return player.getPlaylist();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return null;
    }

    @Override
    public MediaItem2 getCurrentPlaylistItem_impl() {
        // TODO(jaewan): Implement
        return null;
    }

    @Override
    public void prepare_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.prepare();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void fastForward_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.fastForward();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void rewind_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.rewind();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void seekTo_impl(long pos) {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.seekTo(pos);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void skipToPlaylistItem_impl(MediaItem2 item) {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.setCurrentPlaylistItem(item);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void registerPlayerEventCallback_impl(Executor executor, EventCallback callback) {
        if (executor == null) {
            throw new IllegalArgumentException("executor shouldn't be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        ensureCallingThread();
        if (mCallbacks.get(callback) != null) {
            Log.w(TAG, "callback is already added. Ignoring.");
            return;
        }
        mCallbacks.put(callback, executor);
        // TODO(jaewan): Double check if we need this.
        final PlaybackState2 state = getInstance().getPlaybackState();
        executor.execute(() -> callback.onPlaybackStateChanged(state));
    }

    @Override
    public void unregisterPlayerEventCallback_impl(EventCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        ensureCallingThread();
        mCallbacks.remove(callback);
    }

    @Override
    public PlaybackState2 getPlaybackState_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            // TODO(jaewan): Is it safe to be called on any thread?
            //               Otherwise MediaSession2 should cache the result from listener.
            return player.getPlaybackState();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return null;
    }

    @Override
    public void notifyError_impl(int errorCode, int extra) {
        // TODO(jaewan): Implement
    }

    ///////////////////////////////////////////////////
    // Protected or private methods
    ///////////////////////////////////////////////////

    // Enforces developers to call all the methods on the initially given thread
    // because calls from the MediaController2 will be run on the thread.
    // TODO(jaewan): Should we allow calls from the multiple thread?
    //               I prefer this way because allowing multiple thread may case tricky issue like
    //               b/63446360. If the {@link #setPlayer()} with {@code null} can be called from
    //               another thread, transport controls can be called after that.
    //               That's basically the developer's mistake, but they cannot understand what's
    //               happening behind until we tell them so.
    //               If enforcing callling thread doesn't look good, we can alternatively pick
    //               1. Allow calls from random threads for all methods.
    //               2. Allow calls from random threads for all methods, except for the
    //                  {@link #setPlayer()}.
    void ensureCallingThread() {
        // TODO(jaewan): Uncomment or remove
        /*
        if (mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException("Run this on the given thread");
        }*/
    }

    private void notifyPlaybackStateChangedNotLocked(final PlaybackState2 state) {
        ArrayMap<EventCallback, Executor> callbacks = new ArrayMap<>();
        synchronized (mLock) {
            callbacks.putAll(mCallbacks);
        }
        // Notify to callbacks added directly to this session
        for (int i = 0; i < callbacks.size(); i++) {
            final EventCallback callback = callbacks.keyAt(i);
            final Executor executor = callbacks.valueAt(i);
            executor.execute(() -> callback.onPlaybackStateChanged(state));
        }
        // Notify to controllers as well.
        mSessionStub.notifyPlaybackStateChangedNotLocked(state);
    }

    private void notifyErrorNotLocked(String mediaId, int what, int extra) {
        ArrayMap<EventCallback, Executor> callbacks = new ArrayMap<>();
        synchronized (mLock) {
            callbacks.putAll(mCallbacks);
        }
        // Notify to callbacks added directly to this session
        for (int i = 0; i < callbacks.size(); i++) {
            final EventCallback callback = callbacks.keyAt(i);
            final Executor executor = callbacks.valueAt(i);
            executor.execute(() -> callback.onError(mediaId, what, extra));
        }
        // TODO(jaewan): Notify to controllers as well.
    }

    Context getContext() {
        return mContext;
    }

    MediaSession2 getInstance() {
        return mInstance;
    }

    MediaPlayerBase getPlayer() {
        return mPlayer;
    }

    Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    SessionCallback getCallback() {
        return mCallback;
    }

    MediaSession2Stub getSessionStub() {
        return mSessionStub;
    }

    VolumeProvider2 getVolumeProvider() {
        return mVolumeProvider;
    }

    PlaybackInfo getPlaybackInfo() {
        synchronized (mLock) {
            return mPlaybackInfo;
        }
    }

    PendingIntent getSessionActivity() {
        return mSessionActivity;
    }

    private static class MyEventCallback extends EventCallback {
        private final WeakReference<MediaSession2Impl> mSession;
        private final MediaPlayerBase mPlayer;

        private MyEventCallback(MediaSession2Impl session, MediaPlayerBase player) {
            mSession = new WeakReference<>(session);
            mPlayer = player;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState2 state) {
            MediaSession2Impl session = mSession.get();
            if (mPlayer != session.mInstance.getPlayer()) {
                Log.w(TAG, "Unexpected playback state change notifications. Ignoring.",
                        new IllegalStateException());
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onPlaybackStateChanged from player, state=" + state);
            }
            session.notifyPlaybackStateChangedNotLocked(state);
        }

        @Override
        public void onError(String mediaId, int what, int extra) {
            MediaSession2Impl session = mSession.get();
            if (mPlayer != session.mInstance.getPlayer()) {
                Log.w(TAG, "Unexpected playback state change notifications. Ignoring.",
                        new IllegalStateException());
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onError from player, mediaId=" + mediaId + ", what=" + what
                        + ", extra=" + extra);
            }
            session.notifyErrorNotLocked(mediaId, what, extra);
        }
    }

    public static final class CommandImpl implements CommandProvider {
        private static final String KEY_COMMAND_CODE
                = "android.media.media_session2.command.command_code";
        private static final String KEY_COMMAND_CUSTOM_COMMAND
                = "android.media.media_session2.command.custom_command";
        private static final String KEY_COMMAND_EXTRA
                = "android.media.media_session2.command.extra";

        private final Command mInstance;
        private final int mCommandCode;
        // Nonnull if it's custom command
        private final String mCustomCommand;
        private final Bundle mExtra;

        public CommandImpl(Command instance, int commandCode) {
            mInstance = instance;
            mCommandCode = commandCode;
            mCustomCommand = null;
            mExtra = null;
        }

        public CommandImpl(Command instance, @NonNull String action, @Nullable Bundle extra) {
            if (action == null) {
                throw new IllegalArgumentException("action shouldn't be null");
            }
            mInstance = instance;
            mCommandCode = COMMAND_CODE_CUSTOM;
            mCustomCommand = action;
            mExtra = extra;
        }

        public int getCommandCode_impl() {
            return mCommandCode;
        }

        public @Nullable String getCustomCommand_impl() {
            return mCustomCommand;
        }

        public @Nullable Bundle getExtra_impl() {
            return mExtra;
        }

        /**
         * @ 7return a new Bundle instance from the Command
         */
        public Bundle toBundle_impl() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_COMMAND_CODE, mCommandCode);
            bundle.putString(KEY_COMMAND_CUSTOM_COMMAND, mCustomCommand);
            bundle.putBundle(KEY_COMMAND_EXTRA, mExtra);
            return bundle;
        }

        /**
         * @return a new Command instance from the Bundle
         */
        public static Command fromBundle_impl(Context context, Bundle command) {
            int code = command.getInt(KEY_COMMAND_CODE);
            if (code != COMMAND_CODE_CUSTOM) {
                return new Command(context, code);
            } else {
                String customCommand = command.getString(KEY_COMMAND_CUSTOM_COMMAND);
                if (customCommand == null) {
                    return null;
                }
                return new Command(context, customCommand, command.getBundle(KEY_COMMAND_EXTRA));
            }
        }

        @Override
        public boolean equals_impl(Object obj) {
            if (!(obj instanceof CommandImpl)) {
                return false;
            }
            CommandImpl other = (CommandImpl) obj;
            // TODO(jaewan): Should we also compare contents in bundle?
            //               It may not be possible if the bundle contains private class.
            return mCommandCode == other.mCommandCode
                    && TextUtils.equals(mCustomCommand, other.mCustomCommand);
        }

        @Override
        public int hashCode_impl() {
            final int prime = 31;
            return ((mCustomCommand != null)
                    ? mCustomCommand.hashCode() : 0) * prime + mCommandCode;
        }
    }

    /**
     * Represent set of {@link Command}.
     */
    public static class CommandGroupImpl implements CommandGroupProvider {
        private static final String KEY_COMMANDS =
                "android.media.mediasession2.commandgroup.commands";
        private ArraySet<Command> mCommands = new ArraySet<>();
        private final Context mContext;
        private final CommandGroup mInstance;

        public CommandGroupImpl(Context context, CommandGroup instance, Object other) {
            mContext = context;
            mInstance = instance;
            if (other != null && other instanceof CommandGroupImpl) {
                mCommands.addAll(((CommandGroupImpl) other).mCommands);
            }
        }

        @Override
        public void addCommand_impl(Command command) {
            mCommands.add(command);
        }

        @Override
        public void addAllPredefinedCommands_impl() {
            final int COMMAND_CODE_MAX = 22;
            for (int i = 1; i <= COMMAND_CODE_MAX; i++) {
                mCommands.add(new Command(mContext, i));
            }
        }

        @Override
        public void removeCommand_impl(Command command) {
            mCommands.remove(command);
        }

        @Override
        public boolean hasCommand_impl(Command command) {
            return mCommands.contains(command);
        }

        @Override
        public boolean hasCommand_impl(int code) {
            if (code == COMMAND_CODE_CUSTOM) {
                throw new IllegalArgumentException("Use hasCommand(Command) for custom command");
            }
            for (int i = 0; i < mCommands.size(); i++) {
                if (mCommands.valueAt(i).getCommandCode() == code) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return new bundle from the CommandGroup
         * @hide
         */
        @Override
        public Bundle toBundle_impl() {
            ArrayList<Bundle> list = new ArrayList<>();
            for (int i = 0; i < mCommands.size(); i++) {
                list.add(mCommands.valueAt(i).toBundle());
            }
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(KEY_COMMANDS, list);
            return bundle;
        }

        /**
         * @return new instance of CommandGroup from the bundle
         * @hide
         */
        public static @Nullable CommandGroup fromBundle_impl(Context context, Bundle commands) {
            if (commands == null) {
                return null;
            }
            List<Parcelable> list = commands.getParcelableArrayList(KEY_COMMANDS);
            if (list == null) {
                return null;
            }
            CommandGroup commandGroup = new CommandGroup(context);
            for (int i = 0; i < list.size(); i++) {
                Parcelable parcelable = list.get(i);
                if (!(parcelable instanceof Bundle)) {
                    continue;
                }
                Bundle commandBundle = (Bundle) parcelable;
                Command command = Command.fromBundle(context, commandBundle);
                if (command != null) {
                    commandGroup.addCommand(command);
                }
            }
            return commandGroup;
        }
    }

    public static class ControllerInfoImpl implements ControllerInfoProvider {
        private final ControllerInfo mInstance;
        private final int mUid;
        private final String mPackageName;
        private final boolean mIsTrusted;
        private final IMediaSession2Callback mControllerBinder;

        public ControllerInfoImpl(Context context, ControllerInfo instance, int uid,
                int pid, String packageName, IMediaSession2Callback callback) {
            mInstance = instance;
            mUid = uid;
            mPackageName = packageName;

            // TODO(jaewan): Remove this workaround
            if ("com.android.server.media".equals(packageName)) {
                mIsTrusted = true;
            } else if (context.checkPermission(permission.MEDIA_CONTENT_CONTROL, pid, uid) ==
                    PackageManager.PERMISSION_GRANTED) {
                mIsTrusted = true;
            } else {
                // TODO(jaewan): Also consider enabled notification listener.
                mIsTrusted = false;
                // System apps may bind across the user so uid can be differ.
                // Skip sanity check for the system app.
                try {
                    int uidForPackage = context.getPackageManager().getPackageUid(packageName, 0);
                    if (uid != uidForPackage) {
                        throw new IllegalArgumentException("Illegal call from uid=" + uid +
                                ", pkg=" + packageName + ". Expected uid" + uidForPackage);
                    }
                } catch (NameNotFoundException e) {
                    // Rethrow exception with different name because binder methods only accept
                    // RemoteException.
                    throw new IllegalArgumentException(e);
                }
            }
            mControllerBinder = callback;
        }

        @Override
        public String getPackageName_impl() {
            return mPackageName;
        }

        @Override
        public int getUid_impl() {
            return mUid;
        }

        @Override
        public boolean isTrusted_impl() {
            return mIsTrusted;
        }

        @Override
        public int hashCode_impl() {
            return mControllerBinder.hashCode();
        }

        @Override
        public boolean equals_impl(ControllerInfoProvider obj) {
            return equals(obj);
        }

        @Override
        public int hashCode() {
            return mControllerBinder.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ControllerInfoImpl)) {
                return false;
            }
            ControllerInfoImpl other = (ControllerInfoImpl) obj;
            return mControllerBinder.asBinder().equals(other.mControllerBinder.asBinder());
        }

        public ControllerInfo getInstance() {
            return mInstance;
        }

        public IBinder getId() {
            return mControllerBinder.asBinder();
        }

        public IMediaSession2Callback getControllerBinder() {
            return mControllerBinder;
        }

        public static ControllerInfoImpl from(ControllerInfo controller) {
            return (ControllerInfoImpl) controller.getProvider();
        }
    }

    public static class PlaylistParamsImpl implements PlaylistParamsProvider {
        /**
         * Keys used for converting a PlaylistParams object to a bundle object and vice versa.
         */
        private static final String KEY_REPEAT_MODE =
                "android.media.session2.playlistparams2.repeat_mode";
        private static final String KEY_SHUFFLE_MODE =
                "android.media.session2.playlistparams2.shuffle_mode";
        private static final String KEY_MEDIA_METADATA2_BUNDLE =
                "android.media.session2.playlistparams2.metadata2_bundle";

        private Context mContext;
        private PlaylistParams mInstance;
        private @RepeatMode int mRepeatMode;
        private @ShuffleMode int mShuffleMode;
        private MediaMetadata2 mPlaylistMetadata;

        public PlaylistParamsImpl(Context context, PlaylistParams instance,
                @RepeatMode int repeatMode, @ShuffleMode int shuffleMode,
                MediaMetadata2 playlistMetadata) {
            // TODO(jaewan): Sanity check
            mContext = context;
            mInstance = instance;
            mRepeatMode = repeatMode;
            mShuffleMode = shuffleMode;
            mPlaylistMetadata = playlistMetadata;
        }

        public @RepeatMode int getRepeatMode_impl() {
            return mRepeatMode;
        }

        public @ShuffleMode int getShuffleMode_impl() {
            return mShuffleMode;
        }

        public MediaMetadata2 getPlaylistMetadata_impl() {
            return mPlaylistMetadata;
        }

        @Override
        public Bundle toBundle_impl() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_REPEAT_MODE, mRepeatMode);
            bundle.putInt(KEY_SHUFFLE_MODE, mShuffleMode);
            if (mPlaylistMetadata != null) {
                bundle.putBundle(KEY_MEDIA_METADATA2_BUNDLE, mPlaylistMetadata.toBundle());
            }
            return bundle;
        }

        public static PlaylistParams fromBundle(Context context, Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            if (!bundle.containsKey(KEY_REPEAT_MODE) || !bundle.containsKey(KEY_SHUFFLE_MODE)) {
                return null;
            }

            Bundle metadataBundle = bundle.getBundle(KEY_MEDIA_METADATA2_BUNDLE);
            MediaMetadata2 metadata = metadataBundle == null
                    ? null : MediaMetadata2.fromBundle(context, metadataBundle);

            return new PlaylistParams(context,
                    bundle.getInt(KEY_REPEAT_MODE),
                    bundle.getInt(KEY_SHUFFLE_MODE),
                    metadata);
        }
    }

    public static class CommandButtonImpl implements CommandButtonProvider {
        private static final String KEY_COMMAND
                = "android.media.media_session2.command_button.command";
        private static final String KEY_ICON_RES_ID
                = "android.media.media_session2.command_button.icon_res_id";
        private static final String KEY_DISPLAY_NAME
                = "android.media.media_session2.command_button.display_name";
        private static final String KEY_EXTRA
                = "android.media.media_session2.command_button.extra";
        private static final String KEY_ENABLED
                = "android.media.media_session2.command_button.enabled";

        private final CommandButton mInstance;
        private Command mCommand;
        private int mIconResId;
        private String mDisplayName;
        private Bundle mExtra;
        private boolean mEnabled;

        public CommandButtonImpl(Context context, @Nullable Command command, int iconResId,
                @Nullable String displayName, Bundle extra, boolean enabled) {
            mCommand = command;
            mIconResId = iconResId;
            mDisplayName = displayName;
            mExtra = extra;
            mEnabled = enabled;
            mInstance = new CommandButton(this);
        }

        @Override
        public @Nullable Command getCommand_impl() {
            return mCommand;
        }

        @Override
        public int getIconResId_impl() {
            return mIconResId;
        }

        @Override
        public @Nullable String getDisplayName_impl() {
            return mDisplayName;
        }

        @Override
        public @Nullable Bundle getExtra_impl() {
            return mExtra;
        }

        @Override
        public boolean isEnabled_impl() {
            return mEnabled;
        }

        public @NonNull Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putBundle(KEY_COMMAND, mCommand.toBundle());
            bundle.putInt(KEY_ICON_RES_ID, mIconResId);
            bundle.putString(KEY_DISPLAY_NAME, mDisplayName);
            bundle.putBundle(KEY_EXTRA, mExtra);
            bundle.putBoolean(KEY_ENABLED, mEnabled);
            return bundle;
        }

        public static @Nullable CommandButton fromBundle(Context context, Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            CommandButton.Builder builder = new CommandButton.Builder(context);
            builder.setCommand(Command.fromBundle(context, bundle.getBundle(KEY_COMMAND)));
            builder.setIconResId(bundle.getInt(KEY_ICON_RES_ID, 0));
            builder.setDisplayName(bundle.getString(KEY_DISPLAY_NAME));
            builder.setExtra(bundle.getBundle(KEY_EXTRA));
            builder.setEnabled(bundle.getBoolean(KEY_ENABLED));
            try {
                return builder.build();
            } catch (IllegalStateException e) {
                // Malformed or version mismatch. Return null for now.
                return null;
            }
        }

        /**
         * Builder for {@link CommandButton}.
         */
        public static class BuilderImpl implements CommandButtonProvider.BuilderProvider {
            private final Context mContext;
            private final CommandButton.Builder mInstance;
            private Command mCommand;
            private int mIconResId;
            private String mDisplayName;
            private Bundle mExtra;
            private boolean mEnabled;

            public BuilderImpl(Context context, CommandButton.Builder instance) {
                mContext = context;
                mInstance = instance;
                mEnabled = true;
            }

            @Override
            public CommandButton.Builder setCommand_impl(Command command) {
                mCommand = command;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setIconResId_impl(int resId) {
                mIconResId = resId;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setDisplayName_impl(String displayName) {
                mDisplayName = displayName;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setEnabled_impl(boolean enabled) {
                mEnabled = enabled;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setExtra_impl(Bundle extra) {
                mExtra = extra;
                return mInstance;
            }

            @Override
            public CommandButton build_impl() {
                if (mEnabled && mCommand == null) {
                    throw new IllegalStateException("Enabled button needs Command"
                            + " for controller to invoke the command");
                }
                if (mCommand != null && mCommand.getCommandCode() == COMMAND_CODE_CUSTOM
                        && (mIconResId == 0 || TextUtils.isEmpty(mDisplayName))) {
                    throw new IllegalStateException("Custom commands needs icon and"
                            + " and name to display");
                }
                return new CommandButtonImpl(
                        mContext, mCommand, mIconResId, mDisplayName, mExtra, mEnabled).mInstance;
            }
        }
    }

    public static abstract class BuilderBaseImpl<T extends MediaSession2, C extends SessionCallback>
            implements BuilderBaseProvider<T, C> {
        final Context mContext;
        final MediaPlayerBase mPlayer;
        String mId;
        Executor mCallbackExecutor;
        C mCallback;
        VolumeProvider2 mVolumeProvider;
        PendingIntent mSessionActivity;

        /**
         * Constructor.
         *
         * @param context a context
         * @param player a player to handle incoming command from any controller.
         * @throws IllegalArgumentException if any parameter is null, or the player is a
         *      {@link MediaSession2} or {@link MediaController2}.
         */
        // TODO(jaewan): Also need executor
        public BuilderBaseImpl(Context context, MediaPlayerBase player) {
            if (context == null) {
                throw new IllegalArgumentException("context shouldn't be null");
            }
            if (player == null) {
                throw new IllegalArgumentException("player shouldn't be null");
            }
            mContext = context;
            mPlayer = player;
            // Ensure non-null
            mId = "";
        }

        public void setVolumeProvider_impl(VolumeProvider2 volumeProvider) {
            mVolumeProvider = volumeProvider;
        }

        public void setSessionActivity_impl(PendingIntent pi) {
            mSessionActivity = pi;
        }

        public void setId_impl(String id) {
            if (id == null) {
                throw new IllegalArgumentException("id shouldn't be null");
            }
            mId = id;
        }

        public void setSessionCallback_impl(Executor executor, C callback) {
            if (executor == null) {
                throw new IllegalArgumentException("executor shouldn't be null");
            }
            if (callback == null) {
                throw new IllegalArgumentException("callback shouldn't be null");
            }
            mCallbackExecutor = executor;
            mCallback = callback;
        }

        public abstract T build_impl();
    }

    public static class BuilderImpl extends BuilderBaseImpl<MediaSession2, SessionCallback> {
        public BuilderImpl(Context context, Builder instance, MediaPlayerBase player) {
            super(context, player);
        }

        @Override
        public MediaSession2 build_impl() {
            if (mCallbackExecutor == null) {
                mCallbackExecutor = mContext.getMainExecutor();
            }
            if (mCallback == null) {
                mCallback = new SessionCallback(mContext) {};
            }

            return new MediaSession2Impl(mContext, mPlayer, mId, mVolumeProvider,
                    mSessionActivity, mCallbackExecutor, mCallback).getInstance();
        }
    }
}
