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
 * limitations under the License
 */

package com.android.systemui.recents;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_DISABLE_SWIPE_UP;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_SHOW_OVERVIEW_BUTTON;
import static com.android.systemui.shared.system.NavigationBarCompat.InteractionType;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_INPUT_MONITOR;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SUPPORTS_WINDOW_CORNERS;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SYSUI_PROXY;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_WINDOW_CORNER_RADIUS;

import android.annotation.FloatRange;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputMonitor;
import android.view.MotionEvent;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.Prefs;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to send information from overview to launcher with a binder.
 */
@Singleton
public class OverviewProxyService implements CallbackController<OverviewProxyListener>, Dumpable {

    private static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE";

    public static final String TAG_OPS = "OverviewProxyService";
    public static final boolean DEBUG_OVERVIEW_PROXY = false;
    private static final long BACKOFF_MILLIS = 1000;
    private static final long DEFERRED_CALLBACK_MILLIS = 5000;

    // Max backoff caps at 5 mins
    private static final long MAX_BACKOFF_MILLIS = 10 * 60 * 1000;

    // Default interaction flags if swipe up is disabled before connecting to launcher
    private static final int DEFAULT_DISABLE_SWIPE_UP_STATE = FLAG_DISABLE_SWIPE_UP
            | FLAG_SHOW_OVERVIEW_BUTTON;

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mConnectionRunnable = this::internalConnectToCurrentUser;
    private final ComponentName mRecentsComponentName;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final List<OverviewProxyListener> mConnectionCallbacks = new ArrayList<>();
    private final Intent mQuickStepIntent;

    private Region mActiveNavBarRegion;

    private IOverviewProxy mOverviewProxy;
    private int mConnectionBackoffAttempts;
    private @InteractionType int mInteractionFlags;
    private boolean mBound;
    private boolean mIsEnabled;
    private int mCurrentBoundedUserId = -1;
    private float mBackButtonAlpha;
    private MotionEvent mStatusBarGestureDownEvent;
    private float mWindowCornerRadius;
    private boolean mSupportsRoundedCornersOnWindows;

    private ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {

        public void startScreenPinning(int taskId) {
            if (!verifyCaller("startScreenPinning")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    StatusBar statusBar = SysUiServiceProvider.getComponent(mContext,
                            StatusBar.class);
                    if (statusBar != null) {
                        statusBar.showScreenPinningRequest(taskId, false /* allowCancel */);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void onStatusBarMotionEvent(MotionEvent event) {
            if (!verifyCaller("onStatusBarMotionEvent")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                // TODO move this logic to message queue
                mHandler.post(()->{
                    StatusBar bar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
                    if (bar != null) {
                        bar.dispatchNotificationsPanelTouchEvent(event);

                        int action = event.getActionMasked();
                        if (action == ACTION_DOWN) {
                            mStatusBarGestureDownEvent = MotionEvent.obtain(event);
                        }
                        if (action == ACTION_UP || action == ACTION_CANCEL) {
                            mStatusBarGestureDownEvent.recycle();
                            mStatusBarGestureDownEvent = null;
                        }
                        event.recycle();
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void onSplitScreenInvoked() {
            if (!verifyCaller("onSplitScreenInvoked")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                Divider divider = SysUiServiceProvider.getComponent(mContext, Divider.class);
                if (divider != null) {
                    divider.onDockedFirstAnimationFrame();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void onOverviewShown(boolean fromHome) {
            if (!verifyCaller("onOverviewShown")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                        mConnectionCallbacks.get(i).onOverviewShown(fromHome);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setInteractionState(@InteractionType int flags) {
            if (!verifyCaller("setInteractionState")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                if (mInteractionFlags != flags) {
                    mInteractionFlags = flags;
                    mHandler.post(() -> {
                        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                            mConnectionCallbacks.get(i).onInteractionFlagsChanged(flags);
                        }
                    });
                }
            } finally {
                Prefs.putInt(mContext, Prefs.Key.QUICK_STEP_INTERACTION_FLAGS, mInteractionFlags);
                Binder.restoreCallingIdentity(token);
            }
        }

        public Rect getNonMinimizedSplitScreenSecondaryBounds() {
            if (!verifyCaller("getNonMinimizedSplitScreenSecondaryBounds")) {
                return null;
            }
            long token = Binder.clearCallingIdentity();
            try {
                Divider divider = SysUiServiceProvider.getComponent(mContext, Divider.class);
                if (divider != null) {
                    return divider.getView().getNonMinimizedSplitScreenSecondaryBounds();
                }
                return null;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setBackButtonAlpha(float alpha, boolean animate) {
            if (!verifyCaller("setBackButtonAlpha")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mBackButtonAlpha = alpha;
                mHandler.post(() -> {
                    notifyBackButtonAlphaChanged(alpha, animate);
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public float getWindowCornerRadius() {
            if (!verifyCaller("getWindowCornerRadius")) {
                return 0;
            }
            long token = Binder.clearCallingIdentity();
            try {
                return mWindowCornerRadius;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public boolean supportsRoundedCornersOnWindows() {
            if (!verifyCaller("supportsRoundedCornersOnWindows")) {
                return false;
            }
            long token = Binder.clearCallingIdentity();
            try {
                return mSupportsRoundedCornersOnWindows;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
            if (!verifyCaller("onAssistantProgress")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> notifyAssistantProgress(progress));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void startAssistant(Bundle bundle) {
            if (!verifyCaller("startAssistant")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> notifyStartAssistant(bundle));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public Bundle monitorGestureInput(String name, int displayId) {
            if (!verifyCaller("monitorGestureInput")) {
                return null;
            }
            long token = Binder.clearCallingIdentity();
            try {
                InputMonitor monitor =
                        InputManager.getInstance().monitorGestureInput(name, displayId);
                Bundle result = new Bundle();
                result.putParcelable(KEY_EXTRA_INPUT_MONITOR, monitor);
                return result;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private boolean verifyCaller(String reason) {
            final int callerId = Binder.getCallingUserHandle().getIdentifier();
            if (callerId != mCurrentBoundedUserId) {
                Log.w(TAG_OPS, "Launcher called sysui with invalid user: " + callerId + ", reason: "
                        + reason);
                return false;
            }
            return true;
        }
    };

    private final Runnable mDeferredConnectionCallback = () -> {
        Log.w(TAG_OPS, "Binder supposed established connection but actual connection to service "
            + "timed out, trying again");
        retryConnectionWithBackoff();
    };

    private final BroadcastReceiver mLauncherStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateEnabledState();

            // When launcher service is disabled, reset interaction flags because it is inactive
            if (!isEnabled()) {
                mInteractionFlags = getDefaultInteractionFlags();
                Prefs.remove(mContext, Prefs.Key.QUICK_STEP_INTERACTION_FLAGS);
            }

            // Reconnect immediately, instead of waiting for resume to arrive.
            startConnectionToCurrentUser();
        }
    };

    private final ServiceConnection mOverviewServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mHandler.removeCallbacks(mDeferredConnectionCallback);
            mCurrentBoundedUserId = mDeviceProvisionedController.getCurrentUser();
            mConnectionBackoffAttempts = 0;
            mOverviewProxy = IOverviewProxy.Stub.asInterface(service);
            // Listen for launcher's death
            try {
                service.linkToDeath(mOverviewServiceDeathRcpt, 0);
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Lost connection to launcher service", e);
            }

            Bundle params = new Bundle();
            params.putBinder(KEY_EXTRA_SYSUI_PROXY, mSysUiProxy.asBinder());
            params.putFloat(KEY_EXTRA_WINDOW_CORNER_RADIUS, mWindowCornerRadius);
            params.putBoolean(KEY_EXTRA_SUPPORTS_WINDOW_CORNERS, mSupportsRoundedCornersOnWindows);
            try {
                mOverviewProxy.onInitialize(params);
            } catch (RemoteException e) {
                mCurrentBoundedUserId = -1;
                Log.e(TAG_OPS, "Failed to call onInitialize()", e);
            }
            dispatchNavButtonBounds();

            notifyConnectionChanged();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG_OPS, "Null binding of '" + name + "', try reconnecting");
            mCurrentBoundedUserId = -1;
            retryConnectionWithBackoff();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG_OPS, "Binding died of '" + name + "', try reconnecting");
            mCurrentBoundedUserId = -1;
            retryConnectionWithBackoff();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
            mCurrentBoundedUserId = -1;
        }
    };

    private final DeviceProvisionedListener mDeviceProvisionedCallback =
                new DeviceProvisionedListener() {
            @Override
            public void onUserSetupChanged() {
                if (mDeviceProvisionedController.isCurrentUserSetup()) {
                    internalConnectToCurrentUser();
                }
            }

            @Override
            public void onUserSwitched() {
                mConnectionBackoffAttempts = 0;
                internalConnectToCurrentUser();
            }
        };

    // This is the death handler for the binder from the launcher service
    private final IBinder.DeathRecipient mOverviewServiceDeathRcpt
            = this::cleanupAfterDeath;

    @Inject
    public OverviewProxyService(Context context, DeviceProvisionedController provisionController) {
        mContext = context;
        mHandler = new Handler();
        mDeviceProvisionedController = provisionController;
        mConnectionBackoffAttempts = 0;
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        mQuickStepIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        mInteractionFlags = Prefs.getInt(mContext, Prefs.Key.QUICK_STEP_INTERACTION_FLAGS,
                getDefaultInteractionFlags());
        mWindowCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(mContext.getResources());
        mSupportsRoundedCornersOnWindows = ScreenDecorationsUtils
                .supportsRoundedCornersOnWindows(mContext.getResources());

        // Assumes device always starts with back button until launcher tells it that it does not
        mBackButtonAlpha = 1.0f;

        // Listen for the package update changes.
        if (mDeviceProvisionedController.getCurrentUser() == UserHandle.USER_SYSTEM) {
            updateEnabledState();
            mDeviceProvisionedController.addCallback(mDeviceProvisionedCallback);
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            filter.addDataSchemeSpecificPart(mRecentsComponentName.getPackageName(),
                    PatternMatcher.PATTERN_LITERAL);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            mContext.registerReceiver(mLauncherStateChangedReceiver, filter);
        }
    }

    public void notifyBackAction(boolean completed, int downX, int downY, boolean isButton,
            boolean gestureSwipeLeft) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onBackAction(completed, downX, downY, isButton, gestureSwipeLeft);
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to notify back action", e);
        }
    }

    /**
     * Sets the navbar region which can receive touch inputs
     */
    public void onActiveNavBarRegionChanges(Region activeRegion) {
        mActiveNavBarRegion = activeRegion;
        dispatchNavButtonBounds();
    }

    private void dispatchNavButtonBounds() {
        if (mOverviewProxy != null && mActiveNavBarRegion != null) {
            try {
                mOverviewProxy.onActiveNavBarRegionChanges(mActiveNavBarRegion);
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Failed to call onActiveNavBarRegionChanges()", e);
            }
        }
    }

    public float getBackButtonAlpha() {
        return mBackButtonAlpha;
    }

    public void cleanupAfterDeath() {
        if (mStatusBarGestureDownEvent != null) {
            mHandler.post(()-> {
                StatusBar bar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
                if (bar != null) {
                    System.out.println("MERONG dispatchNotificationPanelTouchEvent");
                    mStatusBarGestureDownEvent.setAction(MotionEvent.ACTION_CANCEL);
                    bar.dispatchNotificationsPanelTouchEvent(mStatusBarGestureDownEvent);
                    mStatusBarGestureDownEvent.recycle();
                    mStatusBarGestureDownEvent = null;
                }
            });
        }
        startConnectionToCurrentUser();
    }

    public void startConnectionToCurrentUser() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            mHandler.post(mConnectionRunnable);
        } else {
            internalConnectToCurrentUser();
        }
    }

    private void internalConnectToCurrentUser() {
        disconnectFromLauncherService();

        // If user has not setup yet or already connected, do not try to connect
        if (!mDeviceProvisionedController.isCurrentUserSetup() || !isEnabled()) {
            Log.v(TAG_OPS, "Cannot attempt connection, is setup "
                + mDeviceProvisionedController.isCurrentUserSetup() + ", is enabled "
                + isEnabled());
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);
        Intent launcherServiceIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        try {
            mBound = mContext.bindServiceAsUser(launcherServiceIntent,
                    mOverviewServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    UserHandle.of(mDeviceProvisionedController.getCurrentUser()));
        } catch (SecurityException e) {
            Log.e(TAG_OPS, "Unable to bind because of security error", e);
        }
        if (mBound) {
            // Ensure that connection has been established even if it thinks it is bound
            mHandler.postDelayed(mDeferredConnectionCallback, DEFERRED_CALLBACK_MILLIS);
        } else {
            // Retry after exponential backoff timeout
            retryConnectionWithBackoff();
        }
    }

    private void retryConnectionWithBackoff() {
        if (mHandler.hasCallbacks(mConnectionRunnable)) {
            return;
        }
        final long timeoutMs = (long) Math.min(
                Math.scalb(BACKOFF_MILLIS, mConnectionBackoffAttempts), MAX_BACKOFF_MILLIS);
        mHandler.postDelayed(mConnectionRunnable, timeoutMs);
        mConnectionBackoffAttempts++;
        Log.w(TAG_OPS, "Failed to connect on attempt " + mConnectionBackoffAttempts
                + " will try again in " + timeoutMs + "ms");
    }

    @Override
    public void addCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.add(listener);
        listener.onConnectionChanged(mOverviewProxy != null);
        listener.onInteractionFlagsChanged(mInteractionFlags);
    }

    @Override
    public void removeCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.remove(listener);
    }

    public boolean shouldShowSwipeUpUI() {
        return isEnabled() && ((mInteractionFlags & FLAG_DISABLE_SWIPE_UP) == 0);
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public IOverviewProxy getProxy() {
        return mOverviewProxy;
    }

    public int getInteractionFlags() {
        return mInteractionFlags;
    }

    private void disconnectFromLauncherService() {
        if (mBound) {
            // Always unbind the service (ie. if called through onNullBinding or onBindingDied)
            mContext.unbindService(mOverviewServiceConnection);
            mBound = false;
        }

        if (mOverviewProxy != null) {
            mOverviewProxy.asBinder().unlinkToDeath(mOverviewServiceDeathRcpt, 0);
            mOverviewProxy = null;
            notifyBackButtonAlphaChanged(1f, false /* animate */);
            notifyConnectionChanged();
        }
    }

    private int getDefaultInteractionFlags() {
        // If there is no settings available use device default or get it from settings
        return QuickStepContract.isLegacyMode(mContext)
                ? DEFAULT_DISABLE_SWIPE_UP_STATE
                : 0;
    }

    private void notifyBackButtonAlphaChanged(float alpha, boolean animate) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onBackButtonAlphaChanged(alpha, animate);
        }
    }

    private void notifyConnectionChanged() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onConnectionChanged(mOverviewProxy != null);
        }
    }

    public void notifyQuickStepStarted() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onQuickStepStarted();
        }
    }

    public void notifyQuickScrubStarted() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onQuickScrubStarted();
        }
    }

    private void notifyAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onAssistantProgress(progress);
        }
    }

    private void notifyStartAssistant(Bundle bundle) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).startAssistant(bundle);
        }
    }

    public void notifyAssistantVisibilityChanged(float visibility) {
        try {
            mOverviewProxy.onAssistantVisibilityChanged(visibility);
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onAssistantVisibilityChanged()", e);
        }
    }

    private void updateEnabledState() {
        mIsEnabled = mContext.getPackageManager().resolveServiceAsUser(mQuickStepIntent,
                MATCH_SYSTEM_ONLY,
                ActivityManagerWrapper.getInstance().getCurrentUserId()) != null;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG_OPS + " state:");
        pw.print("  recentsComponentName="); pw.println(mRecentsComponentName);
        pw.print("  isConnected="); pw.println(mOverviewProxy != null);
        pw.print("  isCurrentUserSetup="); pw.println(mDeviceProvisionedController
                .isCurrentUserSetup());
        pw.print("  connectionBackoffAttempts="); pw.println(mConnectionBackoffAttempts);
        pw.print("  interactionFlags="); pw.println(mInteractionFlags);

        pw.print("  quickStepIntent="); pw.println(mQuickStepIntent);
        pw.print("  quickStepIntentResolved="); pw.println(isEnabled());
        pw.print("  navBarMode=");
        pw.println(QuickStepContract.getCurrentInteractionMode(mContext));
    }

    public interface OverviewProxyListener {
        default void onConnectionChanged(boolean isConnected) {}
        default void onQuickStepStarted() {}
        default void onInteractionFlagsChanged(@InteractionType int flags) {}
        default void onOverviewShown(boolean fromHome) {}
        default void onQuickScrubStarted() {}
        default void onBackButtonAlphaChanged(float alpha, boolean animate) {}
        default void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {}
        default void startAssistant(Bundle bundle) {}
    }
}
