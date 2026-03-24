import javax.sound.sampled.*;
import java.util.ArrayList;

// AudioStuff.java
// this file does 2 things:
//   1. listens to the microphone and measures the volume
//   2. text to speech (says things out loud)
//
// i merged these into one file bc they both deal with audio
// originally they were seperate files but thats too many files lol
//
// for the microphone part:
//   uses javax.sound.sampled which is built into java already no download needed!!
//   calculates RMS (root mean square) amplitude to figure out how loud it is
//   that formula is literally on my math reference sheet which is hilarious
//
// for the tts part:
//   on mac it uses the "say" command (its free and built in, genius)
//   on windows i tried to make it use powershell but i dont have windows to test
//   on linux it tries espeak
//
// if theres no mic connected it has a simulateLevel() method that
// makes up fake audio data that kinda looks like real audio
//
// NOTE: the ttsOn and currentVoice variables are static/public
// so Main.java can access them from the settings dialog

public class AudioStuff {

    // ─────────────────────────────────────────────────────────
    // MICROPHONE DETECTION STUFF
    // ─────────────────────────────────────────────────────────

    // other classes implement this to get notified when audio level changes
    // (this is called the Observer pattern, we talked about design patterns in class)
    public interface LevelListener {
        void onLevel(double level, double peak);
    }

    // the actual mic line object from javax.sound
    private TargetDataLine micLine;
    // the background thread that reads audio
    private Thread audioThread;

    // volatile bc multiple threads read these
    private volatile boolean belloRunning = false;
    private volatile double  currentLevel = 0.0;
    private volatile double  peakLvl      = 0.0;

    // list of things that want to know when audio level changes
    private ArrayList<LevelListener> listeners = new ArrayList<>();

    private boolean micActuallyWorking = false; // did the mic open successfully

    // audio format stuff - these numbers are standard, dont change them
    // 44100 hz = cd quality sample rate
    // 16 bit = each sample is a short (2 bytes)
    // 1 channel = mono (not stereo)
    static final float SAMPLE_RATE  = 44100;
    static final int   SAMPLE_BITS  = 16;
    static final int   CHANNELS     = 1;
    static final int   BUFFER_BYTES = 2048;    // how much to read at once
    static final float AMPLIFY      = 7.0f;    // multiply volume up, mics are quiet

    // ─────────────────────────────────────────────────────────
    // TTS STUFF (text to speech)
    // static so they can be changed from settings without an instance
    // ─────────────────────────────────────────────────────────
    public static String  currentVoice = "Alex";
    public static boolean ttsOn        = true;

    // ─────────────────────────────────────────────────────────
    // MICROPHONE METHODS
    // ─────────────────────────────────────────────────────────

    // try to open the microphone, return true if it worked
    public boolean initMic() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, true);
            DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, fmt);

            if (AudioSystem.isLineSupported(lineInfo) == false) {
                System.out.println("[Audio] mic not supported on this computer");
                return false;
            }

            micLine = (TargetDataLine) AudioSystem.getLine(lineInfo);
            micLine.open(fmt, BUFFER_BYTES);
            micActuallyWorking = true;
            System.out.println("[Audio] mic opened!! it works");
            return true;

        } catch (LineUnavailableException glonk) {
            System.out.println("[Audio] mic unavailable: " + glonk.getMessage());
            return false;
        } catch (Exception glonk2) {
            // catch all other exceptions too just in case
            System.out.println("[Audio] something went wrong with mic: " + glonk2.getMessage());
            return false;
        }
    }

    // start listening to mic on a background thread
    public void startListening() {
        if (micActuallyWorking == false || micLine == null) {
            System.out.println("[Audio] cant start listening, no mic");
            return;
        }

        belloRunning = true;
        micLine.start();

        // background thread that continuously reads audio
        audioThread = new Thread(new Runnable() { // not using lambda bc ive seen both ways
            public void run() {
                byte[] buffer = new byte[BUFFER_BYTES];
                System.out.println("[Audio] listening thread started");

                while (belloRunning == true) {
                    int bytesRead = micLine.read(buffer, 0, buffer.length);

                    if (bytesRead > 0) {
                        // calculate RMS to measure loudness
                        // RMS = Root Mean Square
                        // formula: sqrt( (1/n) * sum of (sample squared) )
                        double sumOfSquares = 0;
                        int    numSamples   = 0;

                        // step by 2 bc each sample is 2 bytes (16 bit audio)
                        for (int corn = 0; corn < bytesRead - 1; corn += 2) {
                            // bit math to turn 2 bytes into one 16-bit sample
                            short sample = (short)((buffer[corn] << 8) | (buffer[corn + 1] & 0xFF));
                            sumOfSquares += (double) sample * sample;
                            numSamples++;
                        }

                        if (numSamples > 0) {
                            double rms = Math.sqrt(sumOfSquares / numSamples);

                            // normalize to 0.0 - 1.0 range
                            // 32768 = 2^15 = max value for signed 16bit audio
                            currentLevel = Math.min(1.0, (rms / 32768.0) * AMPLIFY);

                            // peak slowly decays down over time
                            // 0.985 means it drops by 1.5% each frame
                            if (currentLevel > peakLvl) {
                                peakLvl = currentLevel; // new peak
                            } else {
                                peakLvl = peakLvl * 0.985; // decay
                            }

                            // tell all listeners about the new level
                            double lvCopy   = currentLevel;
                            double peakCopy = peakLvl;
                            for (int i = 0; i < listeners.size(); i++) {
                                listeners.get(i).onLevel(lvCopy, peakCopy);
                            }
                        }
                    }

                    // tiny sleep so we dont murder the cpu
                    // 16ms = roughly 60 updates per second
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException ie) {
                        break; // stop loop if interrupted
                    }
                }

                System.out.println("[Audio] listening thread stopped");
            }
        });

        audioThread.setDaemon(true); // daemon thread = dies when main app dies
        audioThread.setName("MicThread");
        audioThread.start();
    }

    public void stopListening() {
        belloRunning = false;
        if (micLine != null) micLine.stop();
    }

    // call this when closing the app
    public void cleanup() {
        stopListening();
        if (micLine != null) {
            micLine.close();
            System.out.println("[Audio] mic closed");
        }
    }

    // add/remove listeners
    public void addListener(LevelListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(LevelListener l) { listeners.remove(l); }

    // getters
    public double  getCurrentLevel()      { return currentLevel;         }
    public double  getPeakLevel()         { return peakLvl;              }
    public boolean isRunning()            { return belloRunning;         }
    public boolean isMicWorking()         { return micActuallyWorking;   }

    // fake audio level for when theres no mic
    // uses a sine wave + random noise so it looks realistic
    // TODO: make this look even more realistic someday
    private static double fakePhaseGlonk = 0.0;
    public static double simulateLevel() {
        fakePhaseGlonk += 0.055;
        double wave  = Math.sin(fakePhaseGlonk) * 0.11 + 0.19; // slowly oscillating base
        double noise = Math.random() * 0.09;                    // random noise on top
        return Math.max(0, Math.min(1.0, wave + noise));
    }

    // ─────────────────────────────────────────────────────────
    // TTS METHODS (text to speech)
    // ─────────────────────────────────────────────────────────

    // speak text out loud
    // runs on separate thread so the UI dosnt freeze up
    public static void speak(String text) {
        if (ttsOn == false) {
            System.out.println("[TTS off] woulda said: " + text);
            return;
        }
        if (text == null || text.isEmpty()) return;

        // run on background thread so UI doesnt freeze
        Thread ttsThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String os = System.getProperty("os.name").toLowerCase();

                    if (os.contains("mac")) {
                        // mac has "say" command built in!! free!!
                        // -v = voice, -r = rate (words per minute)
                        Process p = Runtime.getRuntime().exec(
                            new String[]{"say", "-v", currentVoice, "-r", "195", text}
                        );
                        p.waitFor(); // wait for it to finish before returning
                    } else if (os.contains("win")) {
                        // windows powershell tts
                        // NOTE: havent tested this bc i dont have windows
                        // hopefully it works lol
                        String psCmd = "Add-Type -AssemblyName System.speech; "
                            + "(new-object System.Speech.Synthesis.SpeechSynthesizer)"
                            + ".Speak('" + text.replace("'", "") + "');";
                        Runtime.getRuntime().exec(new String[]{"powershell", "-command", psCmd});
                    } else {
                        // linux - try espeak
                        Runtime.getRuntime().exec(new String[]{"espeak", text});
                    }

                } catch (Exception glonk) {
                    // dont crash if tts fails, just print and move on
                    System.out.println("[TTS] failed to speak: " + glonk.getMessage());
                }
            }
        });

        ttsThread.setDaemon(true);
        ttsThread.setName("SpeechThread");
        ttsThread.start();
    }

    // same as speak but adds "Warning!" at the front
    public static void speakWarning(String text) {
        speak("Warning! " + text);
    }
}
