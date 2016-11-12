/*
 * Copyright (C) 2014 The Android Open Source Project
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

package thjread.ripple;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class Ripple extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1)/20;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Ripple.Engine> mWeakReference;

        public EngineHandler(Ripple.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            Ripple.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mGridPaint;
        Paint mTextPaint;
        Bitmap mTextBitmap;
        Canvas mTextCanvas;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        GridSim mGridSim;
        int num_x = 40;
        int num_y = 20;
        long mLastSec = 0;
        float[][][] mAnimate;
        float[][][] mLastAnimate;
        int mIndex;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(Ripple.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = Ripple.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mGridPaint = new Paint();
            mGridPaint.setColor(resources.getColor(R.color.grid));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();

            mTextBitmap = Bitmap.createBitmap(num_x, num_y, Bitmap.Config.ARGB_8888);
            mTextCanvas = new Canvas(mTextBitmap);

            mGridSim = new GridSim();
            mGridSim.initGrid(num_x, num_y);

            mIndex = 0;
            mAnimate = new float[2500/30 + 1][num_y][num_x];
            mLastAnimate = new float[2500/30 + 1][num_y][num_x];
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            Ripple.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            Ripple.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = Ripple.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            /*float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);*/

            float textSize = 12;
            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mGridSim.initGrid(num_x, num_y);
                    break;
            }
            invalidate();
        }

        private void drawGrid(Canvas canvas, Rect bounds, float[][] grid, float scale) {
            int num_x = grid[0].length;
            int num_y = grid.length;
            int originX = bounds.left;
            //int originY = bounds.top;
            float x_inc = ((float) bounds.width())/num_x;
            float y_inc = x_inc;//((float) bounds.height())/num_y;
            int originY = (int) (bounds.bottom/2 - (num_y/2)*y_inc);

            for (int x=0; x<num_x-1; ++x) {
                for (int y = 0; y < num_y-1; ++y) {
                    float val = 0.5f*(grid[y][x] + grid[y+1][x]) * scale;
                    int col = (int) (255/10*val);
                    if (col >= 0) {
                        mGridPaint.setColor(Color.rgb(col, col, col));
                    } else {
                        mGridPaint.setColor(Color.rgb(0, 0, -col));
                    }
                    canvas.drawLine(originX + x*x_inc, originY + y*y_inc - grid[y][x],
                            originX + x*x_inc, originY + (y+1)*y_inc - grid[y+1][x], mGridPaint);
                    val = 0.5f*(grid[y][x]+grid[y][x+1]) * scale;
                    col = (int) (255/10*val);
                    if (col >= 0) {
                        mGridPaint.setColor(Color.rgb(col, col, col));
                    } else {
                        mGridPaint.setColor(Color.rgb(0, 0, -col));
                    }
                    canvas.drawLine(originX + x*x_inc, originY + y*y_inc - grid[y][x],
                            originX + (x+1)*x_inc, originY + y*y_inc - grid[y][x+1], mGridPaint);
                }
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            if (!mAmbient) {

                if (now >= mLastSec + 5000) {
                    if (now >= mLastSec + 10000) {
                        mCalendar.setTimeInMillis(now + 2500);
                        String text = String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                                mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
                        mTextCanvas.drawRect(0, 0, num_x, num_y, mBackgroundPaint);
                        Rect bs = new Rect();
                        mTextPaint.getTextBounds(text, 0, text.length(), bs);
                        mTextCanvas.drawText(text, 0, bs.height() / 2 + num_y / 2, mTextPaint);
                        mGridSim.setRecordInit(mAnimate, mTextBitmap);
                        mIndex = 2;
                        while (mIndex < mAnimate.length) {
                            mGridSim.simulateGrid(mAnimate, mIndex, 1f / 30);
                            mIndex++;
                        }
                    }
                    mLastSec = now;

                    mCalendar.setTimeInMillis(now + 7500);
                    String text = mAmbient
                            ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                            mCalendar.get(Calendar.MINUTE))
                            : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                            mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
                    mTextCanvas.drawRect(0, 0, num_x, num_y, mBackgroundPaint);
                    Rect bs = new Rect();
                    mTextPaint.getTextBounds(text, 0, text.length(), bs);
                    mTextCanvas.drawText(text, 0, bs.height() / 2 + num_y / 2, mTextPaint);
                    float[][][] a = mLastAnimate;
                    mLastAnimate = mAnimate;
                    mAnimate = a;
                    mGridSim.setRecordInit(mAnimate, mTextBitmap);
                    mIndex = 2;
                } else {
                    while (mIndex <= (now - mLastSec) * 30f / 1000 && mIndex < mAnimate.length) {
                        mGridSim.simulateGrid(mAnimate, mIndex, 1f / 30);
                        mIndex++;
                    }
                }

                int frame = (int) (now - mLastSec);
                int display_frame;
                if (frame <= 2500) {
                    display_frame = (int) ((2500 - frame) * 30f / 1000);
                } else {
                    display_frame = (int) ((frame - 2500) * 30f / 1000);
                }
                float scale = 1.0f;
                if (frame < 400) {
                    scale = (frame/400f)*(2-frame/400f);
                } else if (5000-frame < 400) {
                    scale = ((5000-frame)/400f)*(2-(5000-frame)/400f);
                }
                drawGrid(canvas, bounds, mLastAnimate[display_frame], scale);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
