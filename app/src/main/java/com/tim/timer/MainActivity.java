package com.tim.timer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.SoundPool;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.text.DecimalFormat;
import java.util.Timer;
import java.util.TimerTask;

import static com.tim.timer.R.id.error;
import static com.tim.timer.R.id.set;
import static com.tim.timer.R.raw.ding;

public class MainActivity extends Activity implements NumberPicker.OnValueChangeListener {
    // Used when constructing functions that will be performed asynchronously and need access to the current activity
    private final MainActivity self = this;
    // Whether the timer is currently counting down - used to check if we can start the timer
    private boolean running = false;

    // SoundPool used to play all sound effects
    private SoundPool sound;
    // SoundPool sound IDs of each sound effect - used to play them
    private int dingID;
    private int tickID;
    // SoundPool stream ID of tick sound effect - used to end sound if the timer is canceled
    private int tickStreamID = 0;

    // DecimalFormat used to show rounded values for timer in order to keep a consistent number of decimal digits
    // TODO: make the number of decimal digits customizable via a menu
    private final DecimalFormat df = new DecimalFormat("0.00");
    // Duration of timer - this value stays the same while the timer is running
    private long countdownTime = 0;
    // Time that the timer was started - used with current time to calculate elapsed time
    private long startTime = 0;
    // Time at which the next sound effect should be played
    private long tickTime = 0;
    // There is some lag when playing a sound on a SoundPool that is not playing any sound -
    // by playing a muted sound before the ticking starts, the lag is seriously reduced, as
    // the SoundPool is already running. This is the start time of the muted sound effect
    private long preloadSoundTime = 0;

    // Main loop - see runner
    private Timer t = new Timer();
    // Runnable used t to update the time label and check if it is time for a sound effect or
    // to end the timer
    private final Runnable runner = new Runnable() {
        @Override
        public void run() {
            self.updateTime();
        }
    };
    // Displays time remaining
    private TextView disp;

    // Used to get maxTickStartTime
    private SharedPreferences sharedPref;
    // When the ticking sound effect should start, given that countdownTime is sufficiently
    // larger than this number.
    private int maxTickStartTime;
    // Used when setting maxTickStartTime in settings to track the value of the NumberPicker
    private int tempMaxStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_main);
        super.onCreate(savedInstanceState);
        sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        // maxTickStartTime defaults to 3 - long enough to give warning, not so long that
        // the user looses track. I know this because I am the user
        maxTickStartTime = sharedPref.getInt("com.tim.timer.maxTickStartTime", 3);

        // Make SoundPool that plays one sound at a time
        SoundPool.Builder spBuilder = new SoundPool.Builder();
        spBuilder.setMaxStreams(1);
        sound = spBuilder.build();
        // Get IDs
        tickID = sound.load(this, R.raw.tick, 1);
        dingID = sound.load(this, ding, 1);

        // Grab disp
        disp = findViewById(R.id.disp);
        // Used to display errors
        final TextView error = findViewById(R.id.error);
        // Used to get countdownTime
        final EditText input = findViewById(R.id.input);

        // When start is clicked
        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Immediately grab current time to make it as close to start click as possible -
                // we are dealing in fractions of seconds, this stuff matters
                long tempStartTime = System.nanoTime();

                // If we're already running, tell the user and exit
                if(running) {
                    error.setText(R.string.error_running);
                    return;
                }

                // Ignore start button presses until the timer ends
                running = true;

                // If the entry is not a number, tell the user and exit
                try {
                    countdownTime = (long)(Double.parseDouble(input.getText().toString()) * 1000000000);
                } catch(NumberFormatException e) {
                    error.setText(R.string.error_nonumber);
                    return;
                }

                // Because the input is a double, even though it's immediately converted to a long,
                // we still have to make sure that the number isn't so large that we lose noticeable
                // amounts of precision. If the input is really big, tell the user and exit
                if(countdownTime >= Math.pow(10, 12)) {
                    error.setText(R.string.error_toobig);
                    return;
                }

                // Now that we know we can safely overwrite startTime, do it
                startTime = tempStartTime;
                // Clear error text
                error.setText("");

                // maxTickStartTime but in nanoseconds, which are the units we'll be working in
                // from this point forward. Storing numbers this big in settings is just not worth
                // it when they will always be integers * 10^9
                long maxTickStartTimeNano = (long)maxTickStartTime * 1000000000;
                if(countdownTime >= maxTickStartTimeNano + 500000000) {
                    // If we have at least 0.5 seconds before we need to start ticking to meet
                    // maxTickStartTimeNano, use maxTickStartTimeNano as the first tick time
                    tickTime = maxTickStartTimeNano;
                } else {
                    // Otherwise, find the highest integer that is at least 0.5 seconds less than
                    // countdownTime and use that as the first tick time
                    tickTime = 1000000000 * (long)(countdownTime / 1000000000 - 0.5);
                }
                // Start the silent sound 0.5 seconds before the ticking
                preloadSoundTime = tickTime + 500000000;

                // Start the timer
                TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(runner);
                    }
                };
                // Schedule timer to update every 10 ms
                // TODO: make timer frequency customizable via a menu
                t.scheduleAtFixedRate(task, 0, 10);
            }
        });
        ((Button)findViewById(R.id.stop)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // When stop button is clicked, stop the timer
                self.end();
            }
        });
    }
    protected void updateTime() {
        // Update the time label and check if it is time for a sound effect or to end the timer
        // Called by t every 10 ms

        // tickTime will only be negative if the timer has hit zero, but the timer sometimes
        // runs after it hits zero because it takes a bit for the "stop timer" code to run,
        // as it runs in a different thread. This is to prevent weird stuff
        if(tickTime < 0) {return;}
        long timeLeft = countdownTime - System.nanoTime() + startTime;
        // If we're ready for a sound effect...
        if(timeLeft <= tickTime) {
            // If we're also done with the timer...
            if(tickTime == 0) {
                // Set tickTime to an impossible value to signify that the timer is done
                tickTime = -1;
                // Display the time as zero
                disp.setText(df.format(0));
                // Ding!
                sound.play(dingID, 1, 1, 1, 1, 1);
                // End timer
                this.end();
                // Prevent the rest of the code from executing
                return;
            }
            // If we're not done with the timer, start ticking
            tickStreamID = sound.play(tickID, 1, 1, 1, -1, 1);
            // Next sound effect will be when the timer is done
            tickTime = 0;
        } else if(timeLeft <= preloadSoundTime) {
            // If it's not time for a normal sound effect but it is time to preload, play the muted
            // sound
            sound.play(tickID, 0, 0, 0, 1, 1);
            // Make sure this statement can never be reached again
            preloadSoundTime = -1;
        }
        // Display time left
        disp.setText(df.format((double)timeLeft / 1000000000));
    }
    protected void end() {
        // Cancel timer
        t.cancel();
        // Allow start to be clicked again
        running = false;
        // Stop ticking sound - needed if ended by stop button and can't hurt otherwise
        sound.stop(tickStreamID);
        // Replace the canceled timer
        t = new Timer();
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // When the three dots are pressed, make our options menu
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Right now we only have one option, but a switch case is used because more will likely be
        // implemented and I don't feel like figuring out how to do it later
        switch (item.getItemId()) {
            case R.id.tick_time:
                // Set the current value of the NumberPicker to the current maxTickStartTime
                tempMaxStartTime = maxTickStartTime;
                // Make a dialog
                final Dialog npDialog = new Dialog(MainActivity.this);
                // Set dialog title
                npDialog.setTitle(R.string.npDialog_title);
                // Set dialog contents to the correct XML file
                npDialog.setContentView(R.layout.dialog);

                // Grab the NumberPicker
                final NumberPicker np = npDialog.findViewById(R.id.np);
                // NumberPicker bounds
                np.setMaxValue(Integer.MAX_VALUE);
                np.setMinValue(0);
                // NumberPicker value should always be synchronized with tempMaxStartTime
                np.setValue(tempMaxStartTime);
                // Don't loop to max when going past min or vice-versa - this would be strange
                np.setWrapSelectorWheel(false);
                // Set listener to this activity
                // TODO: move dialog stuff into its own class
                np.setOnValueChangedListener(this);

                // Set and cancel do as one would expect - grab them
                Button set = npDialog.findViewById(R.id.set);
                Button cancel = npDialog.findViewById(R.id.cancel);
                // Make buttons do their thing
                set.setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v) {
                        self.setMaxStartTime();
                        npDialog.dismiss();
                    }
                });
                cancel.setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v) {
                        npDialog.dismiss();
                    }
                });

                // Show dialog
                npDialog.show();
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    @Override
    public void onValueChange(NumberPicker np, int oldVal, int newVal) {
        // Keep tempMaxStartTime synchronized with NumberPicker
        tempMaxStartTime = newVal;
    }
    public void setMaxStartTime() {
        // Set maxTickStartTime to the new value
        maxTickStartTime = tempMaxStartTime;
        // Write maxTickStartTime to the app's settings
        SharedPreferences.Editor edit = sharedPref.edit();
        edit.putInt("com.tim.timer.maxTickStartTime", tempMaxStartTime);
        edit.apply();
    }
}
