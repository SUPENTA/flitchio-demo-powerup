/*

Copyright (c) 2014, TobyRich GmbH
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/
package com.supenta.flitchio.demo.powerup;

import android.app.Activity;
import android.widget.ImageView;
import android.widget.TextView;

import com.supenta.flitchio.demo.powerup.util.Const;
import com.supenta.flitchio.demo.powerup.util.Util;
import com.supenta.flitchio.sdk.ButtonEvent;
import com.supenta.flitchio.sdk.FlitchioController;
import com.supenta.flitchio.sdk.FlitchioListener;
import com.supenta.flitchio.sdk.FlitchioSnapshot;
import com.supenta.flitchio.sdk.InputElement;
import com.supenta.flitchio.sdk.JoystickEvent;

import lib.smartlink.driver.BLESmartplaneService;

/**
 * Acts as a mix of both polling mode (to get the data as fast as possible) and listening mode
 * (to get update about the connection status)
 */
public class FlitchioPoller extends Thread implements FlitchioListener {
    private static final long SLEEP_TIME_MS = 25;

    ImageView slider;
    ImageView throttleNeedle;
    ImageView horizonImage;
    TextView throttleText;
    /* constant only for a specific device */
    float maxCursorRange = -1;  // uninitialized
    private Activity activity;
    private PlaneState planeState;
    private BluetoothDelegate bluetoothDelegate;
    private FlitchioController flitchioController;

    public FlitchioPoller(Activity activity, FlitchioController flitchioController, BluetoothDelegate bluetoothDelegate) {
        this.activity = activity;
        this.planeState = (PlaneState) activity.getApplicationContext();
        this.bluetoothDelegate = bluetoothDelegate;
        this.flitchioController = flitchioController;

        slider = (ImageView) activity.findViewById(R.id.throttleCursor);
        throttleNeedle = (ImageView) activity.findViewById(R.id.imgThrottleNeedle);
        throttleText = (TextView) activity.findViewById(R.id.throttleValue);
        horizonImage = (ImageView) activity.findViewById(R.id.imageHorizon);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            if (((PlaneState) activity.getApplication()).screenLocked) {
                return;
            }

            if (flitchioController != null && flitchioController.isConnected()) {
                FlitchioSnapshot snap = flitchioController.obtainSnapshot();

                processButtonPressure(snap.getButtonPressure(InputElement.BUTTON_TOP));
                processJoystickX(snap.getJoystickX(InputElement.JOYSTICK_BOTTOM));
            }

            try {
                Thread.sleep(SLEEP_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void processButtonPressure(final float pressure) {
        // Our button pressure ranges in [min = 0.0; max = 1.0]
        // But the app expects another range, and inverse: [min = maxCursorRange; max = 0]
        final float throttleValue = (1f - pressure) * maxCursorRange;

        /* If uninitialized, initialize maxCursorRange */
        if (maxCursorRange == -1) {
            float panelHeight = activity.findViewById(R.id.imgPanel).getHeight();
            maxCursorRange = panelHeight * Const.SCALE_FOR_CURSOR_RANGE;
        }

        /* Note: The coordinate system of the screen has the following properties:
         * (0,0) is in the upper-left corner.
         * and there are no negative coordinates, i.e. X and Y always increase.
         */

        float motorSpeed;
        /* check if the touch event went outside the bottom of the panel */
        if (throttleValue >= maxCursorRange) {
            updateSliderY(maxCursorRange);
            motorSpeed = 0;
        /* check if the slider tries to go above the control panel height */
        } else if (throttleValue <= 0) {
            /* 0 corresponds to the max position, because the slider cannot go outside the
             * containing relative layout (the control panel)
             */
            updateSliderY(0);
            motorSpeed = 1; // 100% throttle
        } else {
            updateSliderY(throttleValue);
            motorSpeed = 1 - (throttleValue / maxCursorRange);
        }

        // Adjust if flight assist is on is NOT needed in PowerUp
        // Shai requested 100% throttle even in FlightAssist mode.

        planeState.setMotorSpeed(motorSpeed);
        BLESmartplaneService smartplaneService = bluetoothDelegate.getSmartplaneService();
        setRealMotorSpeed(smartplaneService);
    }

    private void updateSliderY(final float y) {
        slider.post(new Runnable() {
            @Override
            public void run() {
                slider.setY(y);
            }
        });
    }

    private void updateThrottleText(final TextView throttleText, final float adjustedMotorSpeed) {
        throttleText.post(new Runnable() {
            @Override
            public void run() {
                throttleText.setText((short) (adjustedMotorSpeed * 100) + "%");
            }
        });
    }

    /**
     * This method's logic (the communication with the Bluetooth service) comes from former
     * SensorHandler. We have removed the whole class since we Flitchio instead of accelerometer.
     *
     * @param joystickX
     */
    public void processJoystickX(final float joystickX) {
        // We get x in [-1;1]
        // smartplaneService.setRudder expects a value in [-126;126]
        // Not sure yet what flight assist is, it seems to boost the value

        int scaleFactor = planeState.isFlAssistEnabled() ? 126 : 63;
        short newRudder = (short) (scaleFactor * joystickX);

        @SuppressWarnings("SpellCheckingInspection")
        BLESmartplaneService smartplaneService = bluetoothDelegate.getSmartplaneService();
        if (smartplaneService != null) {
            smartplaneService.setRudder(
                    (short) (planeState.rudderReversed ? -newRudder : newRudder)
            );
        }

        horizonImage.post(new Runnable() {
            @Override
            public void run() {
                horizonImage.setRotation(-30 * joystickX);
            }
        });

        // Increase throttle when turning if flight assist is enabled
        if (planeState.isFlAssistEnabled() && !planeState.screenLocked) {
            // TODO dunno what scaler should be exactly
            // double scaler = 1 - Math.cos(rollAngle * Math.PI / 2 / Const.MAX_ROLL_ANGLE);
            double scaler = Math.abs(joystickX);

            if (scaler > 0.3) {
                scaler = 0.3;
            }
            planeState.setScaler(scaler);

            setRealMotorSpeed(smartplaneService);
        }
    }

    /**
     * Called like that to differentiate with the "soft" {@link PlaneState#setMotorSpeed(float)},
     * who does extra scaling, and who doesn;t actually transmit the value to the plane.
     *
     * @param smartplaneService
     */
    private void setRealMotorSpeed(BLESmartplaneService smartplaneService) {
        float adjustedMotorSpeed = planeState.getAdjustedMotorSpeed();
        Util.rotateImageView(throttleNeedle, adjustedMotorSpeed,
                Const.THROTTLE_NEEDLE_MIN_ANGLE, Const.THROTTLE_NEEDLE_MAX_ANGLE);
        updateThrottleText(throttleText, adjustedMotorSpeed);
        if (smartplaneService != null) {
            smartplaneService.setMotor((short) (adjustedMotorSpeed * Const.MAX_MOTOR_SPEED));
        }
    }

    @Override
    public void onFlitchioButtonEvent(InputElement.Button button, ButtonEvent buttonEvent) {
    }

    @Override
    public void onFlitchioJoystickEvent(InputElement.Joystick joystick, JoystickEvent joystickEvent) {

    }

    @Override
    public void onFlitchioStatusChanged(boolean isConnected) {
        Util.setFlitchioStatusConnected(activity, isConnected);

        if (isConnected && !isAlive()) {
            start(); // problem if we start it several times?
        } else if (!isConnected) {
            interrupt();
        }
    }
}
