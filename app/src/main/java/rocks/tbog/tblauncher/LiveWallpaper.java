package rocks.tbog.tblauncher;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Locale;

import rocks.tbog.tblauncher.ui.ListPopup;
import rocks.tbog.tblauncher.utils.GestureDetectorHelper;
import rocks.tbog.tblauncher.utils.UISizes;

public class LiveWallpaper {
    private static final String TAG = "LWP";
    private static final int FLING_DELTA_ANGLE = 33;
    private static final int GD_TOUCH_SLOP_DP = 16;
    private TBLauncherActivity mTBLauncherActivity = null;
    private WallpaperManager mWallpaperManager;
    private final Point mWindowSize = new Point(1, 1);
    private View mContentView;
    private final PointF mFirstTouchOffset = new PointF();
    private final PointF mFirstTouchPos = new PointF();
    private final PointF mLastTouchPos = new PointF();
    private final PointF mWallpaperOffset = new PointF(.5f, .5f);
    private WallpaperSnapAnim mSnapAnimation;
    private VelocityTracker mVelocityTracker;

    public static int SCREEN_COUNT_HORIZONTAL = Integer.parseInt("3");
    public static int SCREEN_COUNT_VERTICAL = Integer.parseInt("1"); // not tested with values != 1

    private boolean lwpScrollPages = true;
    private boolean lwpTouch = true;
    private boolean lwpDrag = false;
    private boolean wpDragAnimate = true;
    private boolean wpReturnCenter = true;
    private boolean wpStickToSides = false;

    private GestureDetector gestureDetector = null;
    private final GestureDetector.SimpleOnGestureListener onGestureListener = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public void onLongPress(@NonNull MotionEvent e) {
            if (!TBApplication.state().isWidgetScreenVisible())
                return;
            View view = mTBLauncherActivity.findViewById(R.id.root_layout);
            onLongClick(view);
        }

        @Override
        public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
            long deltaTimeMs = (e1 != null) ? (e2.getEventTime() - e1.getEventTime()) : 0;
            if (deltaTimeMs > ViewConfiguration.getDoubleTapTimeout())
                return false;
            View view = mTBLauncherActivity.findViewById(R.id.root_layout);
            float xMove = velocityX;
            float yMove = velocityY;
            if (e1 != null) {
                xMove = e1.getRawX() - e2.getRawX();
                yMove = e1.getRawY() - e2.getRawY();
            }
            return LiveWallpaper.this.onFling(view, xMove, yMove, velocityX, velocityY);
        }

        @Override
        public boolean onDoubleTapEvent(@NonNull MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                View view = mTBLauncherActivity.findViewById(R.id.root_layout);
                return onDoubleClick(view);
            }
            return false;
        }

        @Override
        public boolean onSingleTapUp(@NonNull MotionEvent e) {
            // if we have a double tap listener, wait for onSingleTapConfirmed
            if (mTBLauncherActivity.behaviour.hasDoubleClick())
                return true;
            View view = mTBLauncherActivity.findViewById(R.id.root_layout);
            return onClick(view);
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // if we have both a double tap and click, handle click here
            if (mTBLauncherActivity.behaviour.hasDoubleClick()) {
                View view = mTBLauncherActivity.findViewById(R.id.root_layout);
                return onClick(view);
            }
            return false;
        }
    };

    LiveWallpaper() {
//        TypedValue typedValue = new TypedValue();
//        mainActivity.getTheme().resolveAttribute(android.R.attr.windowShowWallpaper, typedValue, true);
//        TypedArray a = mainActivity.obtainStyledAttributes(typedValue.resourceId, new int[]{android.R.attr.windowShowWallpaper});
//        wallpaperIsVisible = a.getBoolean(0, true);
//        a.recycle();
    }

    @NonNull
    public PointF getWallpaperOffset() {
        return mWallpaperOffset;
    }

    @NonNull
    public Point getWindowSize() {
        return mWindowSize;
    }

    public void scroll(MotionEvent e1, MotionEvent e2) {
        cacheWindowSize();
        mFirstTouchPos.set(e1.getRawX(), e1.getRawY());
        mLastTouchPos.set(e2.getRawX(), e2.getRawY());
        float xMove = (mFirstTouchPos.x - mLastTouchPos.x) / mWindowSize.x;
        float yMove = (mFirstTouchPos.y - mLastTouchPos.y) / mWindowSize.y;
        float offsetX = mFirstTouchOffset.x + xMove * 1.01f;
        float offsetY = mFirstTouchOffset.y + yMove * 1.01f;
        updateWallpaperOffset(offsetX, offsetY);
    }

    private int prefGetInt(@NonNull SharedPreferences prefs, @NonNull String key, int defaultValue) {
        String value = prefs.getString(key, null);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public void onCreateActivity(TBLauncherActivity mainActivity) {
        mTBLauncherActivity = mainActivity;

        // load preferences
        {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mainActivity);
            lwpScrollPages = prefs.getBoolean("lwp-scroll-pages", true);
            lwpTouch = prefs.getBoolean("lwp-touch", true);
            lwpDrag = prefs.getBoolean("lwp-drag", false);
            wpDragAnimate = prefs.getBoolean("wp-drag-animate", false);
            wpReturnCenter = prefs.getBoolean("wp-animate-center", true);
            wpStickToSides = prefs.getBoolean("wp-animate-sides", false);
            SCREEN_COUNT_VERTICAL = prefGetInt(prefs, "lwp-page-count-vertical", SCREEN_COUNT_VERTICAL);
            SCREEN_COUNT_HORIZONTAL = prefGetInt(prefs, "lwp-page-count-horizontal", SCREEN_COUNT_HORIZONTAL);
        }

        mWallpaperManager = (WallpaperManager) mainActivity.getSystemService(Context.WALLPAPER_SERVICE);
        assert mWallpaperManager != null;

        // set mContentView before we call updateWallpaperOffset
        mContentView = mainActivity.findViewById(android.R.id.content);
        resetPageCount();

        mSnapAnimation = new WallpaperSnapAnim(this);
        mVelocityTracker = null;
        View root = mainActivity.findViewById(R.id.root_layout);
        root.setOnTouchListener(this::onRootTouch);

        gestureDetector = new GestureDetector(mainActivity, onGestureListener);
        gestureDetector.setIsLongpressEnabled(true);
        GestureDetectorHelper.setGestureDetectorTouchSlop(gestureDetector, UISizes.dp2px(mainActivity, GD_TOUCH_SLOP_DP));
    }

    public void resetPosition() {
        resetPageCount();
    }

    private void resetPageCount() {
        Log.i(TAG, "resetPageCount " + SCREEN_COUNT_HORIZONTAL + "x" + SCREEN_COUNT_VERTICAL);

        float xStep = (SCREEN_COUNT_HORIZONTAL > 1) ? (1.f / (SCREEN_COUNT_HORIZONTAL - 1)) : 0.f;
        float yStep = (SCREEN_COUNT_VERTICAL > 1) ? (1.f / (SCREEN_COUNT_VERTICAL - 1)) : 0.f;
        mWallpaperManager.setWallpaperOffsetSteps(xStep, yStep);

        if (isPreferenceLWPScrollPages()) {
            mTBLauncherActivity.widgetManager.setPageCount(SCREEN_COUNT_HORIZONTAL, SCREEN_COUNT_VERTICAL);
        }

        int centerScreenX = SCREEN_COUNT_HORIZONTAL / 2;
        int centerScreenY = SCREEN_COUNT_VERTICAL / 2;
        updateWallpaperOffset(centerScreenX * xStep, centerScreenY * yStep);
    }

    private static boolean onClick(View view) {
        if (!view.isAttachedToWindow())
            return false;
        return TBApplication.behaviour(view.getContext()).onClick();
    }

    private static boolean onDoubleClick(View view) {
        if (!view.isAttachedToWindow())
            return false;
        return TBApplication.behaviour(view.getContext()).onDoubleClick();
    }

    private static int computeAngle(float x, float y) {
        return (int) (.5 + Math.toDegrees(Math.atan2(y, x)));
    }

    private boolean onFling(View view, float xMove, float yMove, float xVel, float yVel) {
        if (!view.isAttachedToWindow())
            return false;
        final Behaviour behaviour = mTBLauncherActivity.behaviour;

        final int angle;
//        if (-minMovement < xMove && xMove < minMovement && -minMovement < yMove && yMove < minMovement) {
//            // too little movement, use velocity
//            angle = computeAngle(xVel, yVel);
//        } else {
        angle = computeAngle(xMove, yMove);
//        }

        // fling upwards
        if ((90 + FLING_DELTA_ANGLE) > angle && angle > (90 - FLING_DELTA_ANGLE)) {
            Log.d(TAG, String.format(Locale.US, "Angle=%d - fling upward", angle));
            return behaviour.onFlingUp();
        }
        // fling downwards
        else if ((90 + FLING_DELTA_ANGLE) > -angle && -angle > (90 - FLING_DELTA_ANGLE)) {
            Log.d(TAG, String.format(Locale.US, "Angle=%d - fling downward", angle));
            final int posX = (int) mFirstTouchPos.x;
            if (posX < (mWindowSize.x / 2))
                return behaviour.onFlingDownLeft();
            else
                return behaviour.onFlingDownRight();
        }
        // fling left
        else if (FLING_DELTA_ANGLE > angle && angle > -FLING_DELTA_ANGLE) {
            Log.d(TAG, String.format(Locale.US, "Angle=%d - fling left", angle));
            return behaviour.onFlingLeft();
        }
        // fling right
        else if ((180 - FLING_DELTA_ANGLE) < angle || angle < (-180 + FLING_DELTA_ANGLE)) {
            Log.d(TAG, String.format(Locale.US, "Angle=%d - fling right", angle));
            return behaviour.onFlingRight();
        }
        Log.d(TAG, String.format(Locale.US, "Angle=%d - fling direction uncertain", angle));
        return false;
    }

    private void onLongClick(View view) {
        if (!view.isAttachedToWindow()) {
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ListPopup menu = mTBLauncherActivity.widgetManager.getConfigPopup(mTBLauncherActivity);
        TBApplication.getApplication(mTBLauncherActivity).registerPopup(menu);
        int x = (int) (mLastTouchPos.x + .5f);
        int y = (int) (mLastTouchPos.y + .5f);
        menu.showAtLocation(view, Gravity.START | Gravity.TOP, x, y);
    }

    private void cacheWindowSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics windowMetrics = mTBLauncherActivity.getWindowManager().getCurrentWindowMetrics();
            //Insets insets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            Rect windowBound = windowMetrics.getBounds();
            int width = windowBound.width();// - insets.left - insets.right;
            int height = windowBound.height();// - insets.top - insets.bottom;
            mWindowSize.set(width, height);
        } else {
            mTBLauncherActivity.getWindowManager()
                .getDefaultDisplay()
                .getSize(mWindowSize);
        }
    }

    private boolean initializeSnapAnimation() {
        return mSnapAnimation.init(mVelocityTracker);
    }

    boolean onRootTouch(View view, MotionEvent event) {
        if (!view.isAttachedToWindow()) {
            return false;
        }

        Log.d(TAG, "onRootTouch\r\n" + event);
        int actionMasked = event.getActionMasked();
        boolean eventConsumed = false;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                mFirstTouchPos.set(event.getRawX(), event.getRawY());
                mLastTouchPos.set(mFirstTouchPos);
                mFirstTouchOffset.set(mWallpaperOffset);
                cacheWindowSize();
                if (isScrollEnabled()) {
                    mContentView.clearAnimation();
                }
                if (mVelocityTracker != null)
                    mVelocityTracker.recycle();
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(event);
                //send touch event to the LWP
                if (isPreferenceLWPTouch())
                    sendTouchEvent(view, event);
                eventConsumed = true;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                mLastTouchPos.set(event.getRawX(), event.getRawY());
                float xMove = (mFirstTouchPos.x - mLastTouchPos.x) / mWindowSize.x;
                float yMove = (mFirstTouchPos.y - mLastTouchPos.y) / mWindowSize.y;
                if (mVelocityTracker != null)
                    mVelocityTracker.addMovement(event);

                if (isScrollEnabled()) {
                    float offsetX = mFirstTouchOffset.x + xMove * 1.01f;
                    float offsetY = mFirstTouchOffset.y + yMove * 1.01f;
                    updateWallpaperOffset(offsetX, offsetY);
                }

                //send move/drag event to the LWP
                if (isPreferenceLWPDrag())
                    sendTouchEvent(view, event);
                if (isScrollEnabled())
                    eventConsumed = true;
                break;
            }
            case MotionEvent.ACTION_UP: {
                // was this a click?
                float xMove = (mFirstTouchPos.x - mLastTouchPos.x) / mWindowSize.x;
                float yMove = (mFirstTouchPos.y - mLastTouchPos.y) / mWindowSize.y;
                if (mVelocityTracker == null) {
                    Log.d(TAG, String.format(Locale.US, "Move=(%.3f, %.3f)", xMove, yMove));
                } else {
                    mVelocityTracker.addMovement(event);
                    mVelocityTracker.computeCurrentVelocity(1000 / 30); // 1000 provides px per second
                    float xVel = mVelocityTracker.getXVelocity();// / mWindowSize.x;
                    float yVel = mVelocityTracker.getYVelocity();// / mWindowSize.y;
                    Log.d(TAG, String.format(Locale.US, "Velocity=(%.3f, %.3f)\u2248%d\u00b0 Move=(%.3f, %.3f)\u2248%d\u00b0", xVel, yVel, computeAngle(xVel, yVel), xMove, yMove, computeAngle(xMove, yMove)));
                    // snap position if needed
                    if (isScrollEnabled() && initializeSnapAnimation())
                        mContentView.startAnimation(mSnapAnimation);
                }
            }
            // fallthrough
            case MotionEvent.ACTION_CANCEL:
                if (isScrollEnabled()) {
                    if (mVelocityTracker != null) {
                        mVelocityTracker.addMovement(event);

                        mVelocityTracker.computeCurrentVelocity(1000 / 30); // 1000 provides px per second
                        if (initializeSnapAnimation())
                            mContentView.startAnimation(mSnapAnimation);

                        mVelocityTracker.recycle();
                        mVelocityTracker = null;
                    } else {
                        if (initializeSnapAnimation())
                            mContentView.startAnimation(mSnapAnimation);
                    }
                    eventConsumed = true;
                }
                break;
        }

        eventConsumed = gestureDetector.onTouchEvent(event) || eventConsumed;
        Log.d(TAG, "onRootTouch event " + (eventConsumed ? "" : "NOT ") + "consumed");
        return eventConsumed;
    }

    public Context getContext() {
        return mTBLauncherActivity;
    }

    public void onPrefChanged(SharedPreferences prefs, String key) {
        switch (key) {
            case "lwp-scroll-pages":
                lwpScrollPages = prefs.getBoolean("lwp-scroll-pages", true);
                break;
            case "lwp-touch":
                lwpTouch = prefs.getBoolean("lwp-touch", true);
                break;
            case "lwp-drag":
                lwpDrag = prefs.getBoolean("lwp-drag", false);
                break;
            case "wp-drag-animate":
                wpDragAnimate = prefs.getBoolean("wp-drag-animate", false);
                break;
            case "wp-animate-center":
                wpReturnCenter = prefs.getBoolean("wp-animate-center", true);
                break;
            case "wp-animate-sides":
                wpStickToSides = prefs.getBoolean("wp-animate-sides", false);
                break;
            case "lwp-page-count-vertical": {
                int count = prefGetInt(prefs, "lwp-page-count-vertical", SCREEN_COUNT_VERTICAL);
                if (SCREEN_COUNT_VERTICAL != count) {
                    SCREEN_COUNT_VERTICAL = count;
                    resetPageCount();
                }
                break;
            }
            case "lwp-page-count-horizontal": {
                int count = prefGetInt(prefs, "lwp-page-count-horizontal", SCREEN_COUNT_HORIZONTAL);
                if (SCREEN_COUNT_HORIZONTAL != count) {
                    SCREEN_COUNT_HORIZONTAL = count;
                    resetPageCount();
                }
                break;
            }
        }
    }

    private boolean isScrollEnabled() {
        return lwpScrollPages || wpDragAnimate;
    }

    private boolean isPreferenceLWPScrollPages() {
        return lwpScrollPages;
    }

    private boolean isPreferenceLWPTouch() {
        return lwpTouch;
    }

    private boolean isPreferenceLWPDrag() {
        return lwpDrag;
    }

    public boolean isPreferenceWPDragAnimate() {
        return wpDragAnimate;
    }

    public boolean isPreferenceWPReturnCenter() {
        return wpReturnCenter;
    }

    public boolean isPreferenceWPStickToSides() {
        return wpStickToSides;
    }

    private android.os.IBinder getWindowToken() {
        return mContentView != null && mContentView.isAttachedToWindow() ? mContentView.getWindowToken() : null;
    }

    public void updateWallpaperOffset(float offsetX, float offsetY) {
        offsetX = Math.max(0.f, Math.min(1.f, offsetX));
        offsetY = Math.max(0.f, Math.min(1.f, offsetY));
        mWallpaperOffset.set(offsetX, offsetY);
        if (isPreferenceLWPScrollPages()) {
            mTBLauncherActivity.widgetManager.scroll(offsetX, offsetY);
        }
        if (isPreferenceWPDragAnimate()) {
            android.os.IBinder iBinder = getWindowToken();
            if (iBinder != null) {
                mWallpaperManager.setWallpaperOffsets(iBinder, offsetX, offsetY);
            }
        }
    }

    private void sendTouchEvent(int x, int y, int index) {
        android.os.IBinder iBinder = getWindowToken();
        if (iBinder != null) {
            String command = index == 0 ? WallpaperManager.COMMAND_TAP : WallpaperManager.COMMAND_SECONDARY_TAP;
            try {
                mWallpaperManager.sendWallpaperCommand(iBinder, command, x, y, 0, null);
            } catch (Exception e) {
                Log.e(TAG, "sendTouchEvent (" + x + "," + y + ") idx=" + index, e);
            }
        }
    }

    private void sendTouchEvent(View view, MotionEvent event) {
        int pointerCount = event.getPointerCount();
        int[] viewOffset = {0, 0};
        // this will not account for a rotated view
        view.getLocationOnScreen(viewOffset);

        // get index of first finger
        int pointerIndex = event.findPointerIndex(0);
        if (pointerIndex >= 0 && pointerIndex < pointerCount) {
            sendTouchEvent((int) event.getX(pointerIndex) + viewOffset[0], (int) event.getY(pointerIndex) + viewOffset[1], pointerIndex);
        }

        // get index of second finger
        pointerIndex = event.findPointerIndex(1);
        if (pointerIndex >= 0 && pointerIndex < pointerCount) {
            sendTouchEvent((int) event.getX(pointerIndex) + viewOffset[0], (int) event.getY(pointerIndex) + viewOffset[1], pointerIndex);
        }
    }
}
