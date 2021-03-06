/*
 This code is free software; you can redistribute it and/or modify it under
 the terms of the GNU Lesser General Public License as published by the
 Free Software Foundation; either version 3.0 of the License, or (at your
 option) any later version. (See <http://www.gnu.org/copyleft/lesser.html>.)

 This library is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 more details.

 You should have received a copy of the GNU Lesser General Public License
 along with this library; if not, write to the Free Software Foundation, Inc.,
 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA

 Created by: WIN ROEDILY (roedilywinner@gmail.com)
 */
/*
Extractor class will handle all the feature extraction process done by TarsosDSP library.
The difficult part of this class is that we need to adjust the frame and overlap size for it to
extract the frame according to the number of frames we desire.
Saving to CSV method also done in this class.
 */

package com.example.mobilenoiseclassifier;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.mfcc.MFCC;

class Extractor {

    // Log report TAG
    private String TAG  = "Extractor";

    int cepstrum_c = 40;                           // Number of MFCC to extract per frame
    // Save to csv status
    boolean save_csv    = false;
    // BlockingQueue to store features
    BlockingQueue<float[]> mfccs_BQ = new LinkedBlockingQueue<>(2048);
    boolean silence = true;

    // Process counter and status
    private int counter_process = 0;
    private int counter_writer  = 0;
    boolean isDone  = false;
    // CSV Directory
    private File CSV_Dir;

    // PrintWriter for writing to CSV file
    private PrintWriter printWriter = null;

    // Declare the AudioDispatcher value
    private AudioDispatcher audioDispatcher = null;

    // Properties for AudioDispatcher
    private float threshold = -95;
    private int sample_rate = 8000;
    private int size        = 400;
    // Properties for MFCC Extraction
    private int frame_size;                                 // Buffer size
    private int mic_size    = 640;
    private int overlap     = 322;
    private int mic_overlap = 564;
    private int melFilter   = 40;                           // Points for FilterBank
    private float low_freq  = 0f;                           // Lower frequency
    private float hi_freq   = ((float)sample_rate)/2f;      // High frequency is half the sample rate
    private String passed_item;                             // Selected item from ListView
    private boolean mic = false;

    // Thread for save the extracted features to CSV file.
    private final Thread saver    = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                saveToCSV();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    });

    @SuppressLint("SimpleDateFormat")
    private
    SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    /**
     * Initialize the AudioDispatcher to capture from file
     */
    void initDispatcher_file(String item)
    {
        Log.i(TAG, "Create Dispatcher: INITIALIZE");
        passed_item = item;

        audioDispatcher = AudioDispatcherFactory.fromPipe(
                "/storage/emulated/0/CSV/" + passed_item,
                sample_rate, size, overlap);
        frame_size  = size;
        mic = false;
        Log.i(TAG, "Create Dispatcher: COMPLETE");
    }

    /**
     * Initialize the AudioDispatcher to capture from default microphone
     */
    void initDispatcher_mic()
    {
        Log.i(TAG, "Create Dispatcher: INITIALIZE");
        audioDispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sample_rate, mic_size, mic_overlap);
        frame_size  = mic_size;
        mic = true;
        Log.i(TAG, "Create Dispatcher: COMPLETE");
    }

    /**
     * This function is a function where MFCC extraction and storing to BlockingQueue happens.
     */
    void mfcc()
    {
        if (!mfccs_BQ.isEmpty())
            mfccs_BQ.clear();

        Log.d(TAG, "Create MFCC Object: INITIALIZE");
        final MFCC mfccs  = new MFCC(frame_size, sample_rate, cepstrum_c, melFilter, low_freq, hi_freq);
        Log.d(TAG, "Create MFCC Object: COMPLETE");

        Log.d(TAG, "Assign MFCC to Audio Processor: INITIALIZING");
        audioDispatcher.addAudioProcessor(mfccs);
        Log.d(TAG, "Assign MFCC to Audio Processor: COMPLETE");

        Log.d(Thread.currentThread().getName(), "Audio Processor: CALLING");
        Log.i(TAG, "Time: " + dateFormat.format(new Date()));
        audioDispatcher.addAudioProcessor(new AudioProcessor() {
            @Override
            public boolean process(AudioEvent audioEvent) {
                float[] buffer = audioEvent.getFloatBuffer();
                double level = sound_pressure_level(buffer);
                counter_process++;
                // Log.d(Thread.currentThread().getName(), "Get MFCC: EXTRACTING (" + counter_process + ")");
                if (mic) {
                    if (level > threshold) {
                        silence = false;
                        extract();
                    } else {
                        silence = true;
                    }
                } else {
                    extract();
                }

                //Log.d(Thread.currentThread().getName(), "Get MFCC: COMPLETE");

                return true;
            }

            private void extract() {
                try {
                    mfccs_BQ.put(mfccs.getMFCC());
                    if (save_csv)
                        saver.run();
                } catch (Exception e) {
                    Log.e(Thread.currentThread().getName(), "Get MFCC: FAILED");
                    e.printStackTrace();
                }
            }

            private double sound_pressure_level(final float[] buffer) {
                double power = 0.0D;
                for (float element : buffer)
                    power += element * element;

                double value = Math.pow(power, 0.5) / buffer.length;
                return 20.0 * Math.log10(value);
            }

            @Override
            public void processingFinished() {
                Log.i(TAG, "Time: " + dateFormat.format(new Date()));
                Log.d(Thread.currentThread().getName(), "Get MFCC: COMPLETE\n" +
                        "\tProcess: " + counter_process +
                        "\n\tWriter: " + counter_writer);
                isDone = true;
            }
        });

        audioDispatcher.run();
    }

    /**
     * This function is to save our extracted features which is stored in BlockingQueue to a CSV file.
     */
    private void saveToCSV() throws InterruptedException {
        counter_writer++;
        float[] receivedFeatures    = mfccs_BQ.take();

        // Log.d(Thread.currentThread().getName(), "Writing to CSV file: WRITING (" + counter_writer + ")");
        for (float element : receivedFeatures)
        {
            //Log.i(TAG, "Writing elements: WRITING");
            printWriter.print(element + ", ");
            //Log.i(TAG, "Writing elements: COMPLETE");
        }
        printWriter.print("\r\n");
        printWriter.flush();
    }

    /**
     * This function is to check the path to save the CSV file whether it's exist or not.
     */
    void pathChecker() {
        String CSV_Path = "/storage/emulated/0/CSV";

        Log.d(TAG, "Creating new file: INITIALIZING");
        CSV_Dir = new File(CSV_Path);
        Log.d(TAG, "Creating new file: COMPLETE");

        Log.d(TAG, "Checking file path: CHECKING");
        boolean fileExistence = CSV_Dir.exists();
        if (!fileExistence) {
            Log.d(TAG, "Folder not found: CREATING");
            fileExistence = CSV_Dir.mkdirs();
            Log.d(TAG, "Folder created");
            Log.i(TAG, "File Existence: " + fileExistence);
        }
    }

    void csv_creator() {
        String CSV_File;

        try {
            CSV_File    = CSV_Dir.toString() + File.separator + "features_" + passed_item + ".csv";

            Log.d(TAG, "Create PrintWriter: CREATING");
            printWriter = new PrintWriter(new FileWriter(CSV_File, false));
            Log.d(TAG, "Create PrintWriter: COMPLETE --> " + CSV_File);
        } catch (IOException e) {
            Log.e(TAG, "Create PritWriter: FAILED");
            e.printStackTrace();
        }
    }

    /**
     * This is a function to terminate the running AudioDispatcher to make sure no extraction is
     * running anymore.
     * It also interrupt the saverThread and close the PrintWriter.
     */
    void killDispatcher() {
        Log.d(TAG, "Kill Dispatcher: TERMINATING");
        if (!audioDispatcher.isStopped())
        {
            audioDispatcher.stop();
            audioDispatcher = null;
        }
        Log.d(TAG, "Kill Dispatcher: COMPLETE");
    }
}
