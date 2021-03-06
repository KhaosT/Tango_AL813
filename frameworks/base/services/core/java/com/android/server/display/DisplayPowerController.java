/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.lights.LightsManager;

import static com.android.server.display.DisplayManagerService.DEBUG_POWER;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.MathUtils;
import android.util.Slog;
import android.util.Spline;
import android.util.TimeUtils;
import android.view.Display;
import android.view.WindowManagerPolicy;

import java.io.PrintWriter;

// Mediatek AAL support
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;



/**
 * Controls the power state of the display.
 *
 * Handles the proximity sensor, light sensor, and animations between states
 * including the screen off animation.
 *
 * This component acts independently of the rest of the power manager service.
 * In particular, it does not share any state and it only communicates
 * via asynchronous callbacks to inform the power manager that something has
 * changed.
 *
 * Everything this class does internally is serialized on its handler although
 * it may be accessed by other threads from the outside.
 *
 * Note that the power manager service guarantees that it will hold a suspend
 * blocker as long as the display is not ready.  So most of the work done here
 * does not need to worry about holding a suspend blocker unless it happens
 * independently of the display ready signal.
   *
 * For debugging, you can make the color fade and brightness animations run
 * slower by changing the "animator duration scale" option in Development Settings.
 */
final class DisplayPowerController implements AutomaticBrightnessController.Callbacks {
    private static final String TAG = "DisplayPowerController";

    private static boolean DEBUG = true;
    private static final boolean DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT = false;

    private static final String SCREEN_ON_BLOCKED_TRACE_NAME = "Screen on blocked";

    // If true, uses the color fade on animation.
    // We might want to turn this off if we cannot get a guarantee that the screen
    // actually turns on and starts showing new content after the call to set the
    // screen state returns.  Playing the animation can also be somewhat slow.
    private static final boolean USE_COLOR_FADE_ON_ANIMATION = false;

    // The minimum reduction in brightness when dimmed.
    private static final int SCREEN_DIM_MINIMUM_REDUCTION = 10;

    private static final int COLOR_FADE_ON_ANIMATION_DURATION_MILLIS = 10;
    private static final int COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS = 400;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 2;
    private static final int MSG_SCREEN_ON_UNBLOCKED = 3;

    private static final int PROXIMITY_UNKNOWN = -1;
    private static final int PROXIMITY_NEGATIVE = 0;
    private static final int PROXIMITY_POSITIVE = 1;

    // Proximity sensor debounce delay in milliseconds for positive or negative transitions.
    private static final int PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY = 0;
    private static final int PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY = 0;

    // Trigger proximity if distance is less than 5 cm.
    private static final float TYPICAL_PROXIMITY_THRESHOLD = 5.0f;

    // Brightness animation ramp rate in brightness units per second.
    private static final int BRIGHTNESS_RAMP_RATE_FAST = 200;
    private static final int BRIGHTNESS_RAMP_RATE_SLOW = 40;

    // Mediatek AAL support
    private int BRIGHTNESS_RAMP_RATE_BRIGHTEN = BRIGHTNESS_RAMP_RATE_FAST;
    private int BRIGHTNESS_RAMP_RATE_DARKEN = BRIGHTNESS_RAMP_RATE_FAST;

    public static final boolean MTK_AAL_SUPPORT =
            SystemProperties.get("ro.mtk_aal_support").equals("1");

    public static final boolean MTK_ULTRA_DIMMING_SUPPORT = PowerManager.MTK_ULTRA_DIMMING_SUPPORT;

    public static final boolean MTK_AAL_RUNTIME_TUNING_SUPPORT =
            nativeRuntimeTuningIsSupported();

    private static final String MTK_AAL_UPDATE_CONFIG_ACTION = "com.mediatek.aal.update_config";

    private final Object mLock = new Object();

    private final Context mContext;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final DisplayPowerCallbacks mCallbacks;

    // Battery stats.
    private final IBatteryStats mBatteryStats;

    // The lights service.
    private final LightsManager mLights;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The window manager policy.
    private final WindowManagerPolicy mWindowManagerPolicy;

    // The display blanker.
    private final DisplayBlanker mBlanker;

    // The proximity sensor, or null if not available or needed.
    private Sensor mProximitySensor;

    // The doze screen brightness.
    private final int mScreenBrightnessDozeConfig;

    // The dim screen brightness.
    private final int mScreenBrightnessDimConfig;

    // The minimum screen brightness to use in a very dark room.
    private final int mScreenBrightnessDarkConfig;

    // The minimum allowed brightness.
    private final int mScreenBrightnessRangeMinimum;

    // The maximum allowed brightness.
    private final int mScreenBrightnessRangeMaximum;

    // True if auto-brightness should be used.
    private boolean mUseSoftwareAutoBrightnessConfig;

    // True if should use light sensor to automatically determine doze screen brightness.
    private final boolean mAllowAutoBrightnessWhileDozingConfig;

    // True if we should fade the screen while turning it off, false if we should play
    // a stylish color fade animation instead.
    private boolean mColorFadeFadesConfig;

    // The pending power request.
    // Initially null until the first call to requestPowerState.
    // Guarded by mLock.
    private DisplayPowerRequest mPendingRequestLocked;

    // True if a request has been made to wait for the proximity sensor to go negative.
    // Guarded by mLock.
    private boolean mPendingWaitForNegativeProximityLocked;

    // True if the pending power request or wait for negative proximity flag
    // has been changed since the last update occurred.
    // Guarded by mLock.
    private boolean mPendingRequestChangedLocked;

    // Set to true when the important parts of the pending power request have been applied.
    // The important parts are mainly the screen state.  Brightness changes may occur
    // concurrently.
    // Guarded by mLock.
    private boolean mDisplayReadyLocked;

    // Set to true if a power state update is required.
    // Guarded by mLock.
    private boolean mPendingUpdatePowerStateLocked;

    /* The following state must only be accessed by the handler thread. */

    // The currently requested power state.
    // The power controller will progressively update its internal state to match
    // the requested power state.  Initially null until the first update.
    private DisplayPowerRequest mPowerRequest;

    // The current power state.
    // Must only be accessed on the handler thread.
    private DisplayPowerState mPowerState;

    // True if the device should wait for negative proximity sensor before
    // waking up the screen.  This is set to false as soon as a negative
    // proximity sensor measurement is observed or when the device is forced to
    // go to sleep by the user.  While true, the screen remains off.
    private boolean mWaitingForNegativeProximity;

    // The actual proximity sensor threshold value.
    private float mProximityThreshold;

    // Set to true if the proximity sensor listener has been registered
    // with the sensor manager.
    private boolean mProximitySensorEnabled;

    // The debounced proximity sensor state.
    private int mProximity = PROXIMITY_UNKNOWN;

    // The raw non-debounced proximity sensor state.
    private int mPendingProximity = PROXIMITY_UNKNOWN;
    private long mPendingProximityDebounceTime = -1; // -1 if fully debounced

    // True if the screen was turned off because of the proximity sensor.
    // When the screen turns on again, we report user activity to the power manager.
    private boolean mScreenOffBecauseOfProximity;

    // The currently active screen on unblocker.  This field is non-null whenever
    // we are waiting for a callback to release it and unblock the screen.
    private ScreenOnUnblocker mPendingScreenOnUnblocker;

    // True if we were in the process of turning off the screen.
    // This allows us to recover more gracefully from situations where we abort
    // turning off the screen.
    private boolean mPendingScreenOff;

    // True if we have unfinished business and are holding a suspend blocker.
    private boolean mUnfinishedBusiness;

    // The elapsed real time when the screen on was blocked.
    private long mScreenOnBlockStartRealTime;

    // Remembers whether certain kinds of brightness adjustments
    // were recently applied so that we can decide how to transition.
    private boolean mAppliedAutoBrightness;
    private boolean mAppliedDimming;
    private boolean mAppliedLowPower;

    // The controller for the automatic brightness level.
    private AutomaticBrightnessController mAutomaticBrightnessController;

    // Animators.
    private ObjectAnimator mColorFadeOnAnimator;
    private ObjectAnimator mColorFadeOffAnimator;
    private RampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;
    /// M: dismiss ColorFade when IPO boot up, disable ColorFade when IPO shutdown
    private boolean mIPOShutDown = false;
    /// M: disable ColorFade when shutdown
    private boolean mShutDown = false;

    /**
     * Creates the display power controller.
     */
    public DisplayPowerController(Context context,
            DisplayPowerCallbacks callbacks, Handler handler,
            SensorManager sensorManager, DisplayBlanker blanker) {
        mHandler = new DisplayControllerHandler(handler.getLooper());
        mCallbacks = callbacks;

        mBatteryStats = BatteryStatsService.getService();
        mLights = LocalServices.getService(LightsManager.class);
        mSensorManager = sensorManager;
        mWindowManagerPolicy = LocalServices.getService(WindowManagerPolicy.class);
        mBlanker = blanker;
        mContext = context;

        final Resources resources = context.getResources();

        boolean screenBrightnessVirtualValues = false;
        if (MTK_ULTRA_DIMMING_SUPPORT) {
            screenBrightnessVirtualValues = resources.getBoolean(
                    com.mediatek.internal.R.bool.config_screenBrightnessVirtualValues);
        }

        // Mediatek AAL: ultra dimming modified
        int screenBrightnessSettingMinimum = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum), screenBrightnessVirtualValues);
        if (MTK_ULTRA_DIMMING_SUPPORT) {
            screenBrightnessSettingMinimum = PowerManager.ULTRA_DIMMING_BRIGHTNESS_MINIMUM;
        }

        mScreenBrightnessDozeConfig = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze), screenBrightnessVirtualValues);

        mScreenBrightnessDimConfig = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim), screenBrightnessVirtualValues);

        mScreenBrightnessDarkConfig = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDark), screenBrightnessVirtualValues);
        if (mScreenBrightnessDarkConfig > mScreenBrightnessDimConfig) {
            Slog.w(TAG, "Expected config_screenBrightnessDark ("
                    + mScreenBrightnessDarkConfig + ") to be less than or equal to "
                    + "config_screenBrightnessDim (" + mScreenBrightnessDimConfig + ").");
        }
        if (mScreenBrightnessDarkConfig > mScreenBrightnessDimConfig) {
            Slog.w(TAG, "Expected config_screenBrightnessDark ("
                    + mScreenBrightnessDarkConfig + ") to be less than or equal to "
                    + "config_screenBrightnessSettingMinimum ("
                    + screenBrightnessSettingMinimum + ").");
        }

        int screenBrightnessRangeMinimum = Math.min(Math.min(
                screenBrightnessSettingMinimum, mScreenBrightnessDimConfig),
                mScreenBrightnessDarkConfig);

        mScreenBrightnessRangeMaximum = PowerManager.BRIGHTNESS_ON;

        mUseSoftwareAutoBrightnessConfig = resources.getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);

        mAllowAutoBrightnessWhileDozingConfig = resources.getBoolean(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing);

        if (mUseSoftwareAutoBrightnessConfig) {
            int[] lux = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLevels);
            int[] screenBrightness = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLcdBacklightValues);
            int lightSensorWarmUpTimeConfig = resources.getInteger(
                    com.android.internal.R.integer.config_lightSensorWarmupTime);
            final float dozeScaleFactor = resources.getFraction(
                    com.android.internal.R.fraction.config_screenAutoBrightnessDozeScaleFactor,
                    1, 1);
            int[] lux_dark = resources.getIntArray(
                    com.hq.resource.internal.R.array.config_autoBrightnessLevels_dark);
            int[] screenBrightness_dark = resources.getIntArray(
                    com.hq.resource.internal.R.array.config_autoBrightnessLcdBacklightValues_dark);
            // Mediatek AAL: ultra dimming modified
            Spline screenAutoBrightnessSpline = createAutoBrightnessSpline(lux, screenBrightness,
                    screenBrightnessVirtualValues);
            Spline screenAutoBrightnessSpline_dark = createAutoBrightnessSpline(lux_dark, screenBrightness_dark,
                    screenBrightnessVirtualValues);

            if (screenAutoBrightnessSpline == null) {
                Slog.e(TAG, "Error in config.xml.  config_autoBrightnessLcdBacklightValues "
                        + "(size " + screenBrightness.length + ") "
                        + "must be monotic and have exactly one more entry than "
                        + "config_autoBrightnessLevels (size " + lux.length + ") "
                        + "which must be strictly increasing.  "
                        + "Auto-brightness will be disabled.");
                mUseSoftwareAutoBrightnessConfig = false;
            } else {
                int bottom = clampAbsoluteBrightness(screenBrightness[0], screenBrightnessVirtualValues);
                if (mScreenBrightnessDarkConfig > bottom) {
                    Slog.w(TAG, "config_screenBrightnessDark (" + mScreenBrightnessDarkConfig
                            + ") should be less than or equal to the first value of "
                            + "config_autoBrightnessLcdBacklightValues ("
                            + bottom + ").");
                }
                if (bottom < screenBrightnessRangeMinimum) {
                    screenBrightnessRangeMinimum = bottom;
                }
                mAutomaticBrightnessController = new AutomaticBrightnessController(this,
                        handler.getLooper(), sensorManager, screenAutoBrightnessSpline,
                        lightSensorWarmUpTimeConfig, screenBrightnessRangeMinimum,
                        mScreenBrightnessRangeMaximum, dozeScaleFactor);
                if (screenAutoBrightnessSpline_dark != null) {
                    Slog.w(TAG, "wuhuihui add screenAutoBrightnessSpline_dark");
                    mAutomaticBrightnessController.setDarkAutoBrightnessSpline(screenAutoBrightnessSpline_dark);
                } else {
                   Slog.w(TAG, "wuhuihui test screenAutoBrightnessSpline_dark is null");
                }
                if (MTK_AAL_SUPPORT && (lux.length + 1 == screenBrightness.length)) {
                    int[] curve = new int[lux.length + 1 + screenBrightness.length];
                    curve[0] = 0;
                    System.arraycopy(lux, 0, curve, 1, lux.length);
                    System.arraycopy(screenBrightness, 0, curve, lux.length + 1, screenBrightness.length);
                    // We should not write the config to AALService here,
                    // because the service may be not ready yet.
                    mTuningInitCurve = curve;
                }
            }
        }

        mScreenBrightnessRangeMinimum = screenBrightnessRangeMinimum;

        mColorFadeFadesConfig = resources.getBoolean(
                com.android.internal.R.bool.config_animateScreenLights);

        if (!DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT) {
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (mProximitySensor != null) {
                mProximityThreshold = Math.min(mProximitySensor.getMaximumRange(),
                        TYPICAL_PROXIMITY_THRESHOLD);
            }
        }

        if (MTK_AAL_RUNTIME_TUNING_SUPPORT) {
            IntentFilter filter = new IntentFilter(MTK_AAL_UPDATE_CONFIG_ACTION);
            mContext.registerReceiver(new UpdateConfigReceiver(), filter);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    if ("android.intent.action.ACTION_SHUTDOWN_IPO".equals(action)) {
                        if (DEBUG) {
                            Slog.d(TAG, "received ACTION_SHUTDOWN_IPO");
                        }
                        mIPOShutDown = true;
                    } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                        if (DEBUG) {
                            Slog.d(TAG, "received ACTION_SHUTDOWN");
                        }
                        mShutDown = true;
                    }
                }
        }, filter);
    }

    
    /// M:[SmartBook]Dismiss ColorFade for SMB
    public void dismissColorFade() {
        mPowerState.dismissColorFade();
    }

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    public boolean isProximitySensorAvailable() {
        return mProximitySensor != null;
    }

    /**
     * set IPO screen on delay
     */
    public void setIPOScreenOnDelay(int msec){
        mPowerState.setIPOScreenOnDelay(msec);
    }

    /**
     * Requests a new power state.
     * The controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     *
     * @param request The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     * negative proximity before turning the screen back on, assuming the screen
     * was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously (such as turning the screen on), in which case the caller
     * should grab a wake lock, watch for {@link DisplayPowerCallbacks#onStateChanged()}
     * then try the request again later until the state converges.
     */
    public boolean requestPowerState(DisplayPowerRequest request,
            boolean waitForNegativeProximity) {
        if (DEBUG) {
            Slog.d(TAG, "requestPowerState: "
                    + request + ", waitForNegativeProximity=" + waitForNegativeProximity);
        }

        synchronized (mLock) {
            boolean changed = false;

            if (waitForNegativeProximity
                    && !mPendingWaitForNegativeProximityLocked) {
                mPendingWaitForNegativeProximityLocked = true;
                changed = true;
            }

            if (mPendingRequestLocked == null) {
                mPendingRequestLocked = new DisplayPowerRequest(request);
                changed = true;
            } else if (!mPendingRequestLocked.equals(request)) {
                mPendingRequestLocked.copyFrom(request);
                changed = true;
            }

            if (changed) {
                mDisplayReadyLocked = false;
            }

            if (changed && !mPendingRequestChangedLocked) {
                mPendingRequestChangedLocked = true;
                sendUpdatePowerStateLocked();
            }

            return mDisplayReadyLocked;
        }
    }

    private void sendUpdatePowerState() {
        synchronized (mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    private void sendUpdatePowerStateLocked() {
        if (!mPendingUpdatePowerStateLocked) {
            mPendingUpdatePowerStateLocked = true;
            Message msg = mHandler.obtainMessage(MSG_UPDATE_POWER_STATE);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    private void initialize() {
        // Initialize the power state object for the default display.
        // In the future, we might manage multiple displays independently.
        mPowerState = new DisplayPowerState(mBlanker,
                mLights.getLight(LightsManager.LIGHT_ID_BACKLIGHT),
                new ColorFade(Display.DEFAULT_DISPLAY));

        mColorFadeOnAnimator = ObjectAnimator.ofFloat(
                mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 0.0f, 1.0f);
        mColorFadeOnAnimator.setDuration(COLOR_FADE_ON_ANIMATION_DURATION_MILLIS);
        mColorFadeOnAnimator.addListener(mAnimatorListener);

        mColorFadeOffAnimator = ObjectAnimator.ofFloat(
                mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 1.0f, 0.0f);
        mColorFadeOffAnimator.setDuration(COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS);
        mColorFadeOffAnimator.addListener(mAnimatorListener);

        mScreenBrightnessRampAnimator = new RampAnimator<DisplayPowerState>(
                mPowerState, DisplayPowerState.SCREEN_BRIGHTNESS);
        mScreenBrightnessRampAnimator.setListener(mRampAnimatorListener);

        // Initialize screen state for battery stats.
        try {
            mBatteryStats.noteScreenState(mPowerState.getScreenState());
            mBatteryStats.noteScreenBrightness(mPowerState.getScreenBrightness());
        } catch (RemoteException ex) {
            // same process
        }
    }

    private final Animator.AnimatorListener mAnimatorListener = new Animator.AnimatorListener() {
        @Override
        public void onAnimationStart(Animator animation) {
        }
        @Override
        public void onAnimationEnd(Animator animation) {
            sendUpdatePowerState();
        }
        @Override
        public void onAnimationRepeat(Animator animation) {
        }
        @Override
        public void onAnimationCancel(Animator animation) {
        }
    };

    private final RampAnimator.Listener mRampAnimatorListener = new RampAnimator.Listener() {
        @Override
        public void onAnimationEnd() {
            // Mediatek AAL support
            mTuningQuicklyApply = false;
            sendUpdatePowerState();
        }
    };

    private void updatePowerState() {
        // Update the power state request.
        final boolean mustNotify;
        boolean mustInitialize = false;
        boolean autoBrightnessAdjustmentChanged = false;
        Slog.d(TAG, "updatePowerState ");
        synchronized (mLock) {
            mPendingUpdatePowerStateLocked = false;
            if (mPendingRequestLocked == null) {
                return; // wait until first actual power request
            }

            if (mPowerRequest == null) {
                mPowerRequest = new DisplayPowerRequest(mPendingRequestLocked);
                mWaitingForNegativeProximity = mPendingWaitForNegativeProximityLocked;
                mPendingWaitForNegativeProximityLocked = false;
                mPendingRequestChangedLocked = false;
                mustInitialize = true;
            } else if (mPendingRequestChangedLocked) {
                autoBrightnessAdjustmentChanged = (mPowerRequest.screenAutoBrightnessAdjustment
                        != mPendingRequestLocked.screenAutoBrightnessAdjustment);
                mPowerRequest.copyFrom(mPendingRequestLocked);
                mWaitingForNegativeProximity |= mPendingWaitForNegativeProximityLocked;
                mPendingWaitForNegativeProximityLocked = false;
                mPendingRequestChangedLocked = false;
                mDisplayReadyLocked = false;
            }

            mustNotify = !mDisplayReadyLocked;
        }

        // Initialize things the first time the power state is changed.
        if (mustInitialize) {
            initialize();

            if (MTK_AAL_RUNTIME_TUNING_SUPPORT)
                writeInitConfig();
        }

        if (MTK_AAL_RUNTIME_TUNING_SUPPORT) {
            updateRuntimeConfig();
        }

        // Compute the basic display state using the policy.
        // We might override this below based on other factors.
        int state;
        int brightness = PowerManager.BRIGHTNESS_DEFAULT;
        boolean performScreenOffTransition = false;
        switch (mPowerRequest.policy) {
            case DisplayPowerRequest.POLICY_OFF:
                state = Display.STATE_OFF;
                /// M: disable ColorFade when shutdown and IPO shutdown
                if (mShutDown) {
                    mShutDown = false;
                } else if (mIPOShutDown) {
                    // do nothing
                } else {
                    performScreenOffTransition = true;
                }
                break;
            case DisplayPowerRequest.POLICY_DOZE:
                if (mPowerRequest.dozeScreenState != Display.STATE_UNKNOWN) {
                    state = mPowerRequest.dozeScreenState;
                } else {
                    state = Display.STATE_DOZE;
                }
                if (!mAllowAutoBrightnessWhileDozingConfig) {
                    brightness = mPowerRequest.dozeScreenBrightness;
                }
                break;
            case DisplayPowerRequest.POLICY_DIM:
            case DisplayPowerRequest.POLICY_BRIGHT:
            default:
                state = Display.STATE_ON;
                break;
        }
        assert(state != Display.STATE_UNKNOWN);

        // Apply the proximity sensor.
        if (mProximitySensor != null) {
            if (mPowerRequest.useProximitySensor && state != Display.STATE_OFF) {
                setProximitySensorEnabled(true);
                if (!mScreenOffBecauseOfProximity
                        && mProximity == PROXIMITY_POSITIVE) {
                    mScreenOffBecauseOfProximity = true;
                    sendOnProximityPositiveWithWakelock();
                }
            } else if (mWaitingForNegativeProximity
                    && mScreenOffBecauseOfProximity
                    && mProximity == PROXIMITY_POSITIVE
                    && state != Display.STATE_OFF) {
                setProximitySensorEnabled(true);
            } else {
                if (mPowerRequest.useProximitySensor) {
                    if (mScreenOffBecauseOfProximity) {
                        mProximity = PROXIMITY_UNKNOWN;
                    }
                    setProximitySensorEnabled(true);
                } else {
                    setProximitySensorEnabled(false);
                }
                mWaitingForNegativeProximity = false;
            }
            if (mScreenOffBecauseOfProximity
                    && mProximity != PROXIMITY_POSITIVE) {
                mScreenOffBecauseOfProximity = false;
                sendOnProximityNegativeWithWakelock();
            }
        } else {
            mWaitingForNegativeProximity = false;
        }
        if (mScreenOffBecauseOfProximity) {
            state = Display.STATE_OFF;
        }

        /// M: dismiss ColorFade when IPO boot up
        final boolean ipoShutdown = mIPOShutDown;

        // Animate the screen state change unless already animating.
        // The transition may be deferred, so after this point we will use the
        // actual state instead of the desired one.
        animateScreenStateChange(state, performScreenOffTransition);
        state = mPowerState.getScreenState();

        // Use zero brightness when screen is off.
        if (state == Display.STATE_OFF) {
            brightness = PowerManager.BRIGHTNESS_OFF;
        }

        // Configure auto-brightness.
        boolean autoBrightnessEnabled = false;
        if (mAutomaticBrightnessController != null) {
            final boolean autoBrightnessEnabledInDoze = mAllowAutoBrightnessWhileDozingConfig
                    && (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND);
            autoBrightnessEnabled = mPowerRequest.useAutoBrightness
                    && (state == Display.STATE_ON || autoBrightnessEnabledInDoze)
                    && brightness < 0;
            // Mediatek AAL: ultra dimming modified
            mAutomaticBrightnessController.configure(autoBrightnessEnabled || mLowDimmingProtectionEnabled,
                    mPowerRequest.screenAutoBrightnessAdjustment, state != Display.STATE_ON);
        }

        // Apply brightness boost.
        // We do this here after configuring auto-brightness so that we don't
        // disable the light sensor during this temporary state.  That way when
        // boost ends we will be able to resume normal auto-brightness behavior
        // without any delay.
        if (mPowerRequest.boostScreenBrightness
                && brightness != PowerManager.BRIGHTNESS_OFF) {
            brightness = PowerManager.BRIGHTNESS_ON;
        }

        // Apply auto-brightness.
        boolean slowChange = false;
        if (brightness < 0) {
            if (autoBrightnessEnabled) {
                brightness = mAutomaticBrightnessController.getAutomaticScreenBrightness();
            }
            if (brightness >= 0) {
                // Use current auto-brightness value and slowly adjust to changes.
                brightness = clampScreenBrightness(brightness);
                if (mAppliedAutoBrightness && !autoBrightnessAdjustmentChanged) {
                    slowChange = true; // slowly adapt to auto-brightness
                }
                mAppliedAutoBrightness = true;
            } else {
                mAppliedAutoBrightness = false;
            }
        } else {
            mAppliedAutoBrightness = false;
        }

        // Use default brightness when dozing unless overridden.
        if (brightness < 0 && (state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND)) {
            brightness = mScreenBrightnessDozeConfig;
        }

        // Apply manual brightness.
        // Use the current brightness setting from the request, which is expected
        // provide a nominal default value for the case where auto-brightness
        // is not ready yet.
        if (brightness < 0) {
            brightness = clampScreenBrightness(mPowerRequest.screenBrightness);

            // Mediatek AAL: ultra dimming modified
            if (LOW_DIMMING_PROTECTION_SUPPORT) {
                if (!isScreenStateBright()) {
                    // Consider a scenario:
                    // low dimming -> send protection -> screen off -> a while -> screen on
                    // the protection may be ignored during the screen off
                    // We have to resend the protection when screen on
                    mLowDimmingProtectionTriggerBrightness = 0;
                }
            
                mLowDimmingProtectionEnabled = (brightness < LOW_DIMMING_PROTECTION_THRESHOLD);
                mAutomaticBrightnessController.configure(autoBrightnessEnabled || mLowDimmingProtectionEnabled,
                        mPowerRequest.screenAutoBrightnessAdjustment, state != Display.STATE_ON);

                int minBrightness = protectedMinimumBrightness();
                if (getBrightnessSetting() == mPowerRequest.screenBrightness && // the brightness request is from user setting
                        brightness < minBrightness &&
                        brightness != mLowDimmingProtectionTriggerBrightness &&
                        isScreenStateBright())
                {
                    long current = SystemClock.uptimeMillis();
                    mHandler.removeMessages(MSG_PROTECT_LOW_DIMMING);
                    mHandler.sendEmptyMessageAtTime(MSG_PROTECT_LOW_DIMMING,
                            current + LOW_DIMMING_PROTECTION_DURATION);

                    Slog.d(TAG, "Trigger low dimming protection : " + brightness + " < " + minBrightness +
                           ", auto = " + mAutomaticBrightnessController.getAutomaticScreenBrightness());

                    // If we have emitted a protection for some brightness and another updatePowerState() is
                    // invoked, do not remove the protection message and re-emit if the brightness is not chaged by user.
                    // That is, the calling of updatePowerState() is not due to user operations.
                    mLowDimmingProtectionTriggerBrightness = brightness;
                }
            }
        }

        // Apply dimming by at least some minimum amount when user activity
        // timeout is about to expire.
        if (mPowerRequest.policy == DisplayPowerRequest.POLICY_DIM) {
            if (brightness > mScreenBrightnessRangeMinimum) {
                // Mediatek AAL modified
                if (brightness - SCREEN_DIM_MINIMUM_REDUCTION > 0)
                    brightness = brightness - SCREEN_DIM_MINIMUM_REDUCTION;
                else
                    brightness = brightness / 2;
                brightness = Math.max(Math.min(brightness,
                        mScreenBrightnessDimConfig), mScreenBrightnessRangeMinimum);
            }
            if (!mAppliedDimming) {
                slowChange = false;
            }
            mAppliedDimming = true;
        }

        // If low power mode is enabled, cut the brightness level by half
        // as long as it is above the minimum threshold.
        if (mPowerRequest.lowPowerMode) {
            if (brightness > mScreenBrightnessRangeMinimum) {
                brightness = Math.max(brightness / 2, mScreenBrightnessRangeMinimum);
            }
            if (!mAppliedLowPower) {
                slowChange = false;
            }
            mAppliedLowPower = true;
        }

        // Animate the screen brightness when the screen is on or dozing.
        // Skip the animation when the screen is off or suspended.
        if (!mPendingScreenOff) {
            if (state == Display.STATE_ON || state == Display.STATE_DOZE) {
                // Mediatek AAL modified
                int rate = BRIGHTNESS_RAMP_RATE_SLOW;

                if (MTK_AAL_SUPPORT) {
                    if (mTuningQuicklyApply) { // was set in updateRuntimeConfig()
                        slowChange = false;
                    }
                    if (mPowerState.getScreenBrightness() < brightness) {
                        rate = BRIGHTNESS_RAMP_RATE_BRIGHTEN;
                    } else {
                        rate = BRIGHTNESS_RAMP_RATE_DARKEN;
                    }
                }

                if (!slowChange) {
                    rate = BRIGHTNESS_RAMP_RATE_FAST;
                }

                /// M: dismiss ColorFade when IPO boot up
                if (ipoShutdown) {
                    animateScreenBrightness(brightness, 0);
                } else {
                    animateScreenBrightness(brightness, rate);
                }
            }
        }

        // Determine whether the display is ready for use in the newly requested state.
        // Note that we do not wait for the brightness ramp animation to complete before
        // reporting the display is ready because we only need to ensure the screen is in the
        // right power state even as it continues to converge on the desired brightness.
        final boolean ready = mPendingScreenOnUnblocker == null
                && !mColorFadeOnAnimator.isStarted()
                && !mColorFadeOffAnimator.isStarted()
                && mPowerState.waitUntilClean(mCleanListener);
        final boolean finished = ready
                && !mScreenBrightnessRampAnimator.isAnimating();

        // Grab a wake lock if we have unfinished business.
        if (!finished && !mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(TAG, "Unfinished business...");
            }
            mCallbacks.acquireSuspendBlocker();
            mUnfinishedBusiness = true;
        }

        // Notify the power manager when ready.
        if (ready && mustNotify) {
            // Send state change.
            synchronized (mLock) {
                if (!mPendingRequestChangedLocked) {
                    mDisplayReadyLocked = true;

                    if (DEBUG) {
                        Slog.d(TAG, "Display ready!");
                    }
                }
            }
            sendOnStateChangedWithWakelock();
        }

        // Release the wake lock when we have no unfinished business.
        if (finished && mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(TAG, "Finished business...");
            }
            mUnfinishedBusiness = false;
            mCallbacks.releaseSuspendBlocker();
        }
    }

    @Override
    public void updateBrightness() {
        sendUpdatePowerState();
    }

    private void blockScreenOn() {
        if (mPendingScreenOnUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOnUnblocker = new ScreenOnUnblocker();
            mScreenOnBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(TAG, "Blocking screen on until initial contents have been drawn.");
        }
    }

    private void unblockScreenOn() {
        if (mPendingScreenOnUnblocker != null) {
            mPendingScreenOnUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOnBlockStartRealTime;
            Slog.i(TAG, "Unblocked screen on after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
        }
    }

    private boolean setScreenState(int state) {
        if (mPowerState.getScreenState() != state) {
            final boolean wasOn = (mPowerState.getScreenState() != Display.STATE_OFF);
            mPowerState.setScreenState(state);

            // Tell battery stats about the transition.
            try {
                mBatteryStats.noteScreenState(state);
            } catch (RemoteException ex) {
                // same process
            }

            // Tell the window manager what's happening.
            // Temporarily block turning the screen on until the window manager is ready
            // by leaving a black surface covering the screen.  This surface is essentially
            // the final state of the color fade animation.
            boolean isOn = (state != Display.STATE_OFF);
            if (wasOn && !isOn) {
                unblockScreenOn();
                mWindowManagerPolicy.screenTurnedOff();
            } else if (!wasOn && isOn) {
                if (mPowerState.getColorFadeLevel() == 0.0f) {
                    /// M: dismiss ColorFade when IPO boot up
                    if (mIPOShutDown) {
                        mPowerState.setColorFadeLevel(1.0f);
                        mPowerState.dismissColorFade();
                        mIPOShutDown = false;
                        unblockScreenOn();
                    } else {
                        blockScreenOn();
                    }
                } else {
                    unblockScreenOn();
                }
                mWindowManagerPolicy.screenTurningOn(mPendingScreenOnUnblocker);
            }
        }
        return mPendingScreenOnUnblocker == null;
    }

    private int clampScreenBrightness(int value) {
        return MathUtils.constrain(
                value, mScreenBrightnessRangeMinimum, mScreenBrightnessRangeMaximum);
    }

    private void animateScreenBrightness(int target, int rate) {
        if (DEBUG) {
            Slog.d(TAG, "Animating brightness: target=" + target +", rate=" + rate);
        }
        if (mScreenBrightnessRampAnimator.animateTo(target, rate)) {
            try {
                mBatteryStats.noteScreenBrightness(target);
            } catch (RemoteException ex) {
                // same process
            }
        }
    }

    private void animateScreenStateChange(int target, boolean performScreenOffTransition) {
        // If there is already an animation in progress, don't interfere with it.
        if (mColorFadeOnAnimator.isStarted()
                || mColorFadeOffAnimator.isStarted()) {
            return;
        }

        // If we were in the process of turning off the screen but didn't quite
        // finish.  Then finish up now to prevent a jarring transition back
        // to screen on if we skipped blocking screen on as usual.
        if (mPendingScreenOff && target != Display.STATE_OFF) {
            setScreenState(Display.STATE_OFF);
            mPendingScreenOff = false;
        }

        if (target == Display.STATE_ON) {
            // Want screen on.  The contents of the screen may not yet
            // be visible if the color fade has not been dismissed because
            // its last frame of animation is solid black.
            if (!setScreenState(Display.STATE_ON)) {
                return; // screen on blocked
            }
            if (USE_COLOR_FADE_ON_ANIMATION && mPowerRequest.isBrightOrDim()) {
                // Perform screen on animation.
                if (mPowerState.getColorFadeLevel() == 1.0f) {
                    mPowerState.dismissColorFade();
                } else if (mPowerState.prepareColorFade(mContext,
                        mColorFadeFadesConfig ?
                                ColorFade.MODE_FADE :
                                        ColorFade.MODE_WARM_UP)) {
                    mColorFadeOnAnimator.start();
                } else {
                    mColorFadeOnAnimator.end();
                }
            } else {
                // Skip screen on animation.
                mPowerState.setColorFadeLevel(1.0f);
                mPowerState.dismissColorFade();
            }
        } else if (target == Display.STATE_DOZE) {
            // Want screen dozing.
            // Wait for brightness animation to complete beforehand when entering doze
            // from screen on to prevent a perceptible jump because brightness may operate
            // differently when the display is configured for dozing.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() == Display.STATE_ON) {
                return;
            }

            // Set screen state.
            if (!setScreenState(Display.STATE_DOZE)) {
                return; // screen on blocked
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else if (target == Display.STATE_DOZE_SUSPEND) {
            // Want screen dozing and suspended.
            // Wait for brightness animation to complete beforehand unless already
            // suspended because we may not be able to change it after suspension.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() != Display.STATE_DOZE_SUSPEND) {
                return;
            }

            // If not already suspending, temporarily set the state to doze until the
            // screen on is unblocked, then suspend.
            if (mPowerState.getScreenState() != Display.STATE_DOZE_SUSPEND) {
                if (!setScreenState(Display.STATE_DOZE)) {
                    return; // screen on blocked
                }
                setScreenState(Display.STATE_DOZE_SUSPEND); // already on so can't block
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else {
            // Want screen off.
            mPendingScreenOff = true;
            if (mPowerState.getColorFadeLevel() == 0.0f) {
                // Turn the screen off.
                // A black surface is already hiding the contents of the screen.
                setScreenState(Display.STATE_OFF);
                mPendingScreenOff = false;
            } else if (performScreenOffTransition
                    && mPowerState.prepareColorFade(mContext,
                            mColorFadeFadesConfig ?
                                    ColorFade.MODE_FADE : ColorFade.MODE_COOL_DOWN)
                    && mPowerState.getScreenState() != Display.STATE_OFF) {
                // Perform the screen off animation.
                mColorFadeOffAnimator.start();
            } else {
                // Skip the screen off animation and add a black surface to hide the
                // contents of the screen.
                mColorFadeOffAnimator.end();
            }
        }
    }

    private final Runnable mCleanListener = new Runnable() {
        @Override
        public void run() {
            sendUpdatePowerState();
        }
    };

    private void setProximitySensorEnabled(boolean enable) {
        if (enable) {
            if (!mProximitySensorEnabled) {
                if (DEBUG) {
                    Slog.d(TAG, "setProximitySensorEnabled : True");
                }
                // Register the listener.
                // Proximity sensor state already cleared initially.
                mProximitySensorEnabled = true;
                mSensorManager.registerListener(mProximitySensorListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_UI, mHandler);
            }
        } else {
            if (mProximitySensorEnabled) {
                if (DEBUG) {
                    Slog.d(TAG, "setProximitySensorEnabled : False");
                }
                // Unregister the listener.
                // Clear the proximity sensor state for next time.
                mProximitySensorEnabled = false;
                mProximity = PROXIMITY_UNKNOWN;
                mPendingProximity = PROXIMITY_UNKNOWN;
                mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mSensorManager.unregisterListener(mProximitySensorListener);
                clearPendingProximityDebounceTime(); // release wake lock (must be last)
            }
        }
    }

    private void handleProximitySensorEvent(long time, boolean positive) {
        if (mProximitySensorEnabled) {
            if (mPendingProximity == PROXIMITY_NEGATIVE && !positive) {
                return; // no change
            }
            if (mPendingProximity == PROXIMITY_POSITIVE && positive) {
                return; // no change
            }

            // Only accept a proximity sensor reading if it remains
            // stable for the entire debounce delay.  We hold a wake lock while
            // debouncing the sensor.
            mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
            if (positive) {
                mPendingProximity = PROXIMITY_POSITIVE;
                setPendingProximityDebounceTime(
                        time + PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY); // acquire wake lock
            } else {
                mPendingProximity = PROXIMITY_NEGATIVE;
                setPendingProximityDebounceTime(
                        time + PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY); // acquire wake lock
            }

            // Debounce the new sensor reading.
            debounceProximitySensor();
        }
    }

    private void debounceProximitySensor() {
        if (mProximitySensorEnabled
                && mPendingProximity != PROXIMITY_UNKNOWN
                && mPendingProximityDebounceTime >= 0) {
            final long now = SystemClock.uptimeMillis();
            if (mPendingProximityDebounceTime <= now) {
                // Sensor reading accepted.  Apply the change then release the wake lock.
                mProximity = mPendingProximity;
                updatePowerState();
                clearPendingProximityDebounceTime(); // release wake lock (must be last)
            } else {
                // Need to wait a little longer.
                // Debounce again later.  We continue holding a wake lock while waiting.
                Message msg = mHandler.obtainMessage(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, mPendingProximityDebounceTime);
            }
        }
    }

    private void clearPendingProximityDebounceTime() {
        if (mPendingProximityDebounceTime >= 0) {
            mPendingProximityDebounceTime = -1;
            mCallbacks.releaseSuspendBlocker(); // release wake lock
        }
    }

    private void setPendingProximityDebounceTime(long debounceTime) {
        if (mPendingProximityDebounceTime < 0) {
            mCallbacks.acquireSuspendBlocker(); // acquire wake lock
        }
        mPendingProximityDebounceTime = debounceTime;
    }

    private void sendOnStateChangedWithWakelock() {
        mCallbacks.acquireSuspendBlocker();
        mHandler.post(mOnStateChangedRunnable);
    }

    private final Runnable mOnStateChangedRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onStateChanged();
            mCallbacks.releaseSuspendBlocker();
        }
    };

    private void sendOnProximityPositiveWithWakelock() {
        mCallbacks.acquireSuspendBlocker();
        mHandler.post(mOnProximityPositiveRunnable);
    }

    private final Runnable mOnProximityPositiveRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityPositive();
            mCallbacks.releaseSuspendBlocker();
        }
    };

    private void sendOnProximityNegativeWithWakelock() {
        mCallbacks.acquireSuspendBlocker();
        mHandler.post(mOnProximityNegativeRunnable);
    }

    private final Runnable mOnProximityNegativeRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityNegative();
            mCallbacks.releaseSuspendBlocker();
        }
    };

    public void dump(final PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Display Power Controller Locked State:");
            pw.println("  mDisplayReadyLocked=" + mDisplayReadyLocked);
            pw.println("  mPendingRequestLocked=" + mPendingRequestLocked);
            pw.println("  mPendingRequestChangedLocked=" + mPendingRequestChangedLocked);
            pw.println("  mPendingWaitForNegativeProximityLocked="
                    + mPendingWaitForNegativeProximityLocked);
            pw.println("  mPendingUpdatePowerStateLocked=" + mPendingUpdatePowerStateLocked);
        }

        pw.println();
        pw.println("Display Power Controller Configuration:");
        pw.println("  mScreenBrightnessDozeConfig=" + mScreenBrightnessDozeConfig);
        pw.println("  mScreenBrightnessDimConfig=" + mScreenBrightnessDimConfig);
        pw.println("  mScreenBrightnessDarkConfig=" + mScreenBrightnessDarkConfig);
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mUseSoftwareAutoBrightnessConfig=" + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mAllowAutoBrightnessWhileDozingConfig=" +
                mAllowAutoBrightnessWhileDozingConfig);
        pw.println("  mColorFadeFadesConfig=" + mColorFadeFadesConfig);

        mHandler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                dumpLocal(pw);
            }
        }, 1000);

        DEBUG = DEBUG_POWER;
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("Display Power Controller Thread State:");
        pw.println("  mPowerRequest=" + mPowerRequest);
        pw.println("  mWaitingForNegativeProximity=" + mWaitingForNegativeProximity);

        pw.println("  mProximitySensor=" + mProximitySensor);
        pw.println("  mProximitySensorEnabled=" + mProximitySensorEnabled);
        pw.println("  mProximityThreshold=" + mProximityThreshold);
        pw.println("  mProximity=" + proximityToString(mProximity));
        pw.println("  mPendingProximity=" + proximityToString(mPendingProximity));
        pw.println("  mPendingProximityDebounceTime="
                + TimeUtils.formatUptime(mPendingProximityDebounceTime));
        pw.println("  mScreenOffBecauseOfProximity=" + mScreenOffBecauseOfProximity);
        pw.println("  mAppliedAutoBrightness=" + mAppliedAutoBrightness);
        pw.println("  mAppliedDimming=" + mAppliedDimming);
        pw.println("  mAppliedLowPower=" + mAppliedLowPower);
        pw.println("  mPendingScreenOnUnblocker=" + mPendingScreenOnUnblocker);
        pw.println("  mPendingScreenOff=" + mPendingScreenOff);

        pw.println("  mScreenBrightnessRampAnimator.isAnimating()=" +
                mScreenBrightnessRampAnimator.isAnimating());

        if (mColorFadeOnAnimator != null) {
            pw.println("  mColorFadeOnAnimator.isStarted()=" +
                    mColorFadeOnAnimator.isStarted());
        }
        if (mColorFadeOffAnimator != null) {
            pw.println("  mColorFadeOffAnimator.isStarted()=" +
                    mColorFadeOffAnimator.isStarted());
        }

        if (mPowerState != null) {
            mPowerState.dump(pw);
        }

        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.dump(pw);
        }

    }

    private static String proximityToString(int state) {
        switch (state) {
            case PROXIMITY_UNKNOWN:
                return "Unknown";
            case PROXIMITY_NEGATIVE:
                return "Negative";
            case PROXIMITY_POSITIVE:
                return "Positive";
            default:
                return Integer.toString(state);
        }
    }

    // Mediatek AAL: ultra dimming modified
    private static Spline createAutoBrightnessSpline(int[] lux, int[] brightness, boolean virtualValues) {
        try {
            final int n = brightness.length;
            float[] x = new float[n];
            float[] y = new float[n];
            // Mediatek AAL: ultra dimming modified
            y[0] = normalizeAbsoluteBrightness(brightness[0], virtualValues);
            for (int i = 1; i < n; i++) {
                x[i] = lux[i - 1];
                y[i] = normalizeAbsoluteBrightness(brightness[i], virtualValues);
            }

            Spline spline = Spline.createSpline(x, y);
            if (DEBUG) {
                Slog.d(TAG, "Auto-brightness spline: " + spline);
                for (float v = 1f; v < lux[lux.length - 1] * 1.25f; v *= 1.25f) {
                    Slog.d(TAG, String.format("  %7.1f: %7.1f", v, spline.interpolate(v)));
                }
            }
            return spline;
        } catch (IllegalArgumentException ex) {
            Slog.e(TAG, "Could not create auto-brightness spline.", ex);
            return null;
        }
    }

    // Mediatek AAL: ultra dimming modified
    private static float normalizeAbsoluteBrightness(int value, boolean virtualValue) {
        return (float)clampAbsoluteBrightness(value, virtualValue) / PowerManager.BRIGHTNESS_ON;
    }

    // Mediatek AAL: ultra dimming modified
    private static int clampAbsoluteBrightness(int value, boolean virtualValue) {
        if (!virtualValue)
            value = PowerManager.dimmingPhysicalToVirtual(value);
        return MathUtils.constrain(value, PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
    }

    private final class DisplayControllerHandler extends Handler {
        public DisplayControllerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_POWER_STATE:
                    updatePowerState();
                    break;

                case MSG_PROXIMITY_SENSOR_DEBOUNCED:
                    debounceProximitySensor();
                    break;

                case MSG_SCREEN_ON_UNBLOCKED:
                    if (mPendingScreenOnUnblocker == msg.obj) {
                        unblockScreenOn();
                        updatePowerState();
                    }
                    break;

                case MSG_PROTECT_LOW_DIMMING:
                    handleProtectLowDimming();
                    break;
            }
        }
    }

    private final SensorEventListener mProximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mProximitySensorEnabled) {
                final long time = SystemClock.uptimeMillis();
                final float distance = event.values[0];
                boolean positive = distance >= 0.0f && distance < mProximityThreshold;
                handleProximitySensorEvent(time, positive);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    private final class ScreenOnUnblocker implements WindowManagerPolicy.ScreenOnListener {
        @Override
        public void onScreenOn() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_ON_UNBLOCKED, this);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    // Mediatek AAL support

    // Must sync with IAALService::AdaptFieldId
    private static final int TUNING_ALI2BLI_CURVE_LENGTH = 0;
    private static final int TUNING_ALI2BLI_CURVE = 1;
    private static final int TUNING_BLI_RAMP_RATE_BRIGHTEN = 2;
    private static final int TUNING_BLI_RAMP_RATE_DARKEN = 3;

    private int[] mTuningInitCurve = null;
    private int mTuningAli2BliSerial = 0;
    private int mTuningBliBrightenSerial = -1;
    private int mTuningBliDarkenSerial = -1;
    private boolean mTuningQuicklyApply = false;

    private static native boolean nativeRuntimeTuningIsSupported();
    private static native int nativeSetTuningInt(int field, int value);
    private static native int nativeSetTuningIntArray(int field, int[] array);
    private static native int nativeGetTuningInt(int field);
    private static native int nativeGetTuningSerial(int field);
    private static native void nativeGetTuningIntArray(int field, int[] array);
    public static native void nativeSetDebouncedAmbientLight(int ambientLight);

    // Sync the initial config to AAL service
    private void writeInitConfig() {
        synchronized (mLock) {
            if (mTuningInitCurve != null) {
                mTuningAli2BliSerial = nativeSetTuningIntArray(TUNING_ALI2BLI_CURVE, mTuningInitCurve);
                mTuningInitCurve = null;
            }
            if (mTuningBliBrightenSerial == -1) {
                mTuningBliBrightenSerial = nativeSetTuningInt(TUNING_BLI_RAMP_RATE_BRIGHTEN,
                        BRIGHTNESS_RAMP_RATE_BRIGHTEN);
            }
            if (mTuningBliDarkenSerial == -1) {
                mTuningBliDarkenSerial = nativeSetTuningInt(TUNING_BLI_RAMP_RATE_DARKEN,
                        BRIGHTNESS_RAMP_RATE_DARKEN);
            }
        }
    }

    private void updateRuntimeConfig() {
        synchronized (mLock) {
            boolean updated = false;
            int serial;

            serial = nativeGetTuningSerial(TUNING_ALI2BLI_CURVE);
            if (serial != mTuningAli2BliSerial) {
                int length = nativeGetTuningInt(TUNING_ALI2BLI_CURVE_LENGTH);
                if (length > 0) {
                    int[] curve = new int[length * 2];
                    nativeGetTuningIntArray(TUNING_ALI2BLI_CURVE, curve);

                    if (curve[0] != 0) {
                        Slog.w(TAG, "AALRuntimeTuning: lux[0] is not 0: " + curve[0]);
                    }

                    int[] lux = new int[length - 1];
                    int[] brightness = new int[length];

                    System.arraycopy(curve, 1, lux, 0, length - 1);
                    System.arraycopy(curve, length, brightness, 0, length);

                    Spline spline = createAutoBrightnessSpline(lux, brightness, false);
                    if (spline != null) {
                        mAutomaticBrightnessController.setScreenAutoBrightnessSpline(spline);

                        mTuningAli2BliSerial = serial;
                        updated = true;

                        Slog.d(TAG, "AALRuntimeTuning: curve updated. Length = " + length + ", serial = " + serial);
                        Slog.d(TAG, "AALRuntimeTuning: lux = " + curve[0] + "-" + lux[lux.length - 1] +
                                " brightness = " + brightness[0] + "-" + brightness[brightness.length - 1]);
                    } else {
                        Slog.e(TAG, "AALRuntimeTuning: invalid curve is given.");

                        StringBuffer buffer = new StringBuffer();
                        buffer.append("AALRuntimeTuning: curve = ");
                        for (int i = 0; i < length; i++) {
                            buffer.append(curve[i]);
                            buffer.append(":");
                            buffer.append(curve[length + i]);
                            buffer.append(" ");
                        }
                        Slog.e(TAG, buffer.toString());
                    }
                } else {
                    Slog.e(TAG, "AALRuntimeTuning: invalid curve length: " + length);
                }
            }

            if (updated) {
                mAutomaticBrightnessController.updateRuntimeConfig();
                mTuningQuicklyApply = true;
            }

            serial = nativeGetTuningSerial(TUNING_BLI_RAMP_RATE_BRIGHTEN);
            if (serial != mTuningBliBrightenSerial) {
                BRIGHTNESS_RAMP_RATE_BRIGHTEN = nativeGetTuningInt(TUNING_BLI_RAMP_RATE_BRIGHTEN);
                if (BRIGHTNESS_RAMP_RATE_BRIGHTEN <= 0)
                    BRIGHTNESS_RAMP_RATE_BRIGHTEN = BRIGHTNESS_RAMP_RATE_SLOW;
                mTuningBliBrightenSerial= serial;

                Slog.d(TAG, "AALRuntimeTuning: brighten = " + BRIGHTNESS_RAMP_RATE_BRIGHTEN + ", serial = " + serial);
            }

            serial = nativeGetTuningSerial(TUNING_BLI_RAMP_RATE_DARKEN);
            if (serial != mTuningBliDarkenSerial) {
                BRIGHTNESS_RAMP_RATE_DARKEN = nativeGetTuningInt(TUNING_BLI_RAMP_RATE_DARKEN);
                if (BRIGHTNESS_RAMP_RATE_DARKEN <= 0)
                    BRIGHTNESS_RAMP_RATE_DARKEN = BRIGHTNESS_RAMP_RATE_SLOW;
                mTuningBliDarkenSerial = serial;

                Slog.d(TAG, "AALRuntimeTuning: darken = " + BRIGHTNESS_RAMP_RATE_DARKEN + ", serial = " + serial);
            }
        }
    }

    private class UpdateConfigReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.d(TAG, "AALRuntimeTuning: UpdateConfigReceiver received an intent.");
            sendUpdatePowerState();
        }
    }

    // Mediatek AAL: ultra dimming support
    private static final boolean LOW_DIMMING_PROTECTION_SUPPORT = MTK_ULTRA_DIMMING_SUPPORT;

    // The time which allow low dimming
    private static final long LOW_DIMMING_PROTECTION_DURATION = 5000;

    // Allow protection only if brightness < LOW_DIMMING_PROTECTION_THRESHOLD
    private static final int LOW_DIMMING_PROTECTION_THRESHOLD = 40;

    private static final int MSG_PROTECT_LOW_DIMMING = 100;

    private static final String MTK_AAL_LOW_DIMMING_PROTECTION_TRIGGERED =
            "com.mediatek.aal.low_dimming_protection_triggered";

    private boolean mLowDimmingProtectionEnabled = false;
    private int mLowDimmingProtectionTriggerBrightness = 0;

    private boolean isScreenStateBright() {
        return (mPowerRequest.policy == DisplayPowerRequest.POLICY_BRIGHT);
    }

    private int protectedMinimumBrightness() {
        int autoBrightness = mAutomaticBrightnessController.getAutomaticScreenBrightness();

        // Calculate the minimum brightness from auto brightness value
        // return Math.min(Math.max(1, autoBrightness / 8), LOW_DIMMING_PROTECTION_THRESHOLD);

        return (autoBrightness < LOW_DIMMING_PROTECTION_THRESHOLD * 2) ? 1 : LOW_DIMMING_PROTECTION_THRESHOLD;
    }

    private int getBrightnessSetting() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mPowerRequest.screenBrightness,
                UserHandle.USER_CURRENT);
    }

    // Handler of MSG_PROTECT_LOW_DIMMING
    private void handleProtectLowDimming() {
        if (!mAppliedAutoBrightness) {
            int minBrightness = protectedMinimumBrightness();
            
            if (isScreenStateBright() &&
                    !mPowerRequest.useAutoBrightness &&
                    getBrightnessSetting() == mPowerRequest.screenBrightness &&
                    mPowerRequest.screenBrightness < LOW_DIMMING_PROTECTION_THRESHOLD &&
                    mPowerRequest.screenBrightness < minBrightness)
            {
                Slog.d(TAG, "Low dimming protection : " + mPowerRequest.screenBrightness + " -> " + minBrightness +
                        ", auto = " + mAutomaticBrightnessController.getAutomaticScreenBrightness());
            
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, minBrightness,
                        UserHandle.USER_CURRENT);

                Intent intent = new Intent(MTK_AAL_LOW_DIMMING_PROTECTION_TRIGGERED);
                mContext.sendBroadcast(intent);
            }
        }

        // Let the protection can be checked again if minBrightness changed
        mLowDimmingProtectionTriggerBrightness = 0;
    }
    
}
