/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.deskclock.alarms;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;

import com.android.deskclock.AlarmMultiPlayer;
import com.android.deskclock.LogUtils;
import com.android.deskclock.R;
import com.android.deskclock.Utils;
import com.android.deskclock.provider.AlarmInstance;

import java.io.IOException;

/**
 * Manages playing ringtone and vibrating the device.
 */
public class AlarmKlaxon {
    private static final long[] sVibratePattern = new long[] { 500, 500 };

    // Volume suggested by media team for in-call alarms.
    private static final float IN_CALL_VOLUME = 0.125f;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build();

    // 5sec * 7 volume levels = 30sec till max volume
    private static final long INCREASING_VOLUME_DELAY = 5000;
    private static final int INCREASING_VOLUME_START = 1;
    private static final int INCREASING_VOLUME_DELTA = 1;

    private static boolean sStarted = false;
    private static AudioManager sAudioManager = null;
    private static AlarmMultiPlayer sMediaPlayer = null;

    private static int sCurrentVolume = INCREASING_VOLUME_START;
    private static int sAlarmVolumeSetting;

    // Internal messages
    private static final int INCREASING_VOLUME = 1001;

    private static Handler sHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case INCREASING_VOLUME:
                    if (sStarted && sMediaPlayer != null && sMediaPlayer.isPlaying()) {
                        sCurrentVolume += INCREASING_VOLUME_DELTA;
                        sAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, sCurrentVolume, 0);
                        if (sCurrentVolume < sAlarmVolumeSetting) {
                            sHandler.sendEmptyMessageDelayed(INCREASING_VOLUME,
                                    INCREASING_VOLUME_DELAY);
                        }
                    }
                    break;
            }
        }
    };


    public static void stop(Context context) {
        LogUtils.v("AlarmKlaxon.stop()");

        if (sStarted) {
            sStarted = false;

            sHandler.removeMessages(INCREASING_VOLUME);

            // Stop audio playing
            if (sMediaPlayer != null) {
                sMediaPlayer.stop();
                sMediaPlayer.release();
                sMediaPlayer = null;

                // reset to default from before
                sAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, sAlarmVolumeSetting, 0);
                sAudioManager.abandonAudioFocus(null);
                sAudioManager = null;
            }

            ((Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE)).cancel();
        }
    }

    public static void start(final Context context, AlarmInstance instance,
            boolean inTelephoneCall) {
        LogUtils.v("AlarmKlaxon.start()");
        // Make sure we are stop before starting
        stop(context);

        if (!AlarmInstance.NO_RINGTONE_URI.equals(instance.mRingtone)) {
            Uri alarmNoise = instance.mRingtone;
            // Fall back on the default alarm if the database does not have an
            // alarm stored.
            if (alarmNoise == null) {
                // Try to get the actual default first, this will be the one set by the user
                alarmNoise = RingtoneManager.getActualDefaultRingtoneUri(context,
                        RingtoneManager.TYPE_ALARM);
                // if the actual default is null, fallback to the system default.
                if (alarmNoise == null) {
                    alarmNoise = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                }
                LogUtils.v("Using default alarm: " + alarmNoise.toString());
            } else if (!Utils.isRingToneUriValid(context, alarmNoise)) {
                alarmNoise = RingtoneManager.getActualDefaultRingtoneUri(context,
                        RingtoneManager.TYPE_ALARM);
            }

            final Context appContext = context.getApplicationContext();
            sAudioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            // save current value
            sAlarmVolumeSetting = sAudioManager.getStreamVolume(AudioManager.STREAM_ALARM);

            if (instance.mIncreasingVolume) {
                sCurrentVolume = INCREASING_VOLUME_START;
                sAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, sCurrentVolume, 0);
            }

            if (sMediaPlayer != null) {
                if (sMediaPlayer.isPlaying()) {
                    sMediaPlayer.stop();
                }
            } else {
                sMediaPlayer = new AlarmMultiPlayer(context);
                sMediaPlayer.setOnErrorListener(new OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        LogUtils.e("Error occurred while playing audio. Stopping AlarmKlaxon.");
                        AlarmKlaxon.stop(context);
                        return true;
                    }
                });
            }

            try {
                // Check if we are in a call. If we are, use the in-call alarm
                // resource at a low volume to not disrupt the call.
                if (inTelephoneCall) {
                    LogUtils.v("Using the in-call alarm");
                    sMediaPlayer.setVolume(IN_CALL_VOLUME, IN_CALL_VOLUME);
                    setDataSourceFromResource(context, sMediaPlayer, R.raw.in_call_alarm);
                } else {
                    sMediaPlayer.setDataSource(context, alarmNoise);
                }
                startAlarm(context, sMediaPlayer, instance);
            } catch (Exception ex) {
                LogUtils.v("Using the fallback ringtone");
                // The alarmNoise may be on the sd card which could be busy right
                // now. Use the fallback ringtone.
                try {
                    // Must reset the media player to clear the error state.
                    sMediaPlayer.reset();
                    setDataSourceFromResource(context, sMediaPlayer, R.raw.fallbackring);
                    startAlarm(context, sMediaPlayer, instance);
                } catch (Exception ex2) {
                    // At this point we just don't play anything.
                    LogUtils.e("Failed to play fallback ringtone", ex2);
                }
            }
        }

        if (instance.mVibrate) {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(sVibratePattern, 0, VIBRATION_ATTRIBUTES);
        }

        sStarted = true;
    }

    // Do the common stuff when starting the alarm.
    private static void startAlarm(Context context, AlarmMultiPlayer player,
            AlarmInstance instance) throws IOException {
        // do not play alarms if stream volume is 0 (typically because ringer mode is silent).
        if (sAudioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setLooping(true);
            player.prepare();
            sAudioManager.requestAudioFocus(null,
                    AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            player.start();
        } else if (context.getResources().getBoolean(R.bool.config_ring_alarm_force)) {
            sAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                    sAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setLooping(true);
            player.prepare();
            sAudioManager.requestAudioFocus(null,
                    AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            player.start();
         }

        if (instance.mIncreasingVolume && sCurrentVolume < sAlarmVolumeSetting) {
            sHandler.sendEmptyMessageDelayed(INCREASING_VOLUME, INCREASING_VOLUME_DELAY);
        }
    }

    private static void setDataSourceFromResource(Context context, AlarmMultiPlayer player, int res)
            throws IOException {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
        }
    }
}
