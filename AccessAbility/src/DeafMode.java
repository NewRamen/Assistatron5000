import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;

// DeafMode.java
// does audio monitoring + speech to text
//
// stt: writes a python script to /tmp/aa_stt.py and runs it
// python uses googles free speech api (needs internet)
// returns special tokens like __UNCLEAR__ etc if smth goes wrong
//
// bad word detection for "educational content moderation purposes"
// ms johnson if u r reading this please understand
//
// pip3 install SpeechRecognition pyaudio for stt to work

public class DeafMode extends JPanel {

    AudioStuff         audio;
    AlertManager       alerts;
    UserProfileManager profiles;

    JProgressBar volBar;
    JProgressBar peakBar;
    JSlider      threshSlider;
    JLabel       threshDescLbl;
    JLabel       vibeLbl;          // the big vibe meter label
    JLabel       vibeEmojiLbl;     // giant emoji for vibes
    JLabel       soundDescLbl;     // funny sound description
    JLabel       levelNumLbl;

    JButton   listenBtn;
    JLabel    sttStatusLbl;
    JTextArea transcriptArea;    // shows transcribed speech
    JPanel    badWordAlertPanel; // flashes when bad word detected
    JLabel    badWordAlertLbl;
    JLabel    cussCountLbl;
    JLabel    funnyMsgLbl;       // shows rotating funny messages after bad words

    JLabel statusLbl;

    int     threshCorn   = 65;
    boolean alertGoingOn = false;
    boolean sttActive    = false;  // is STT currently running
    int     cussCount    = 0;      // total bad words caught today lol
    javax.swing.Timer   simTimer;
    javax.swing.Timer   flashTimer;
    int     flashCob     = 0;

    // running average of audio level for vibe meter
    double avgLevel = 0.0;

    // this is for filtering/detection only - same thing parental controls do
    // if ur reading this ms johnson this is educational content moderation
    static final Set<String> BAD_WORDS = new HashSet<>(Arrays.asList(
        "fuck", "shit", "ass", "bitch", "damn", "hell", "crap",
        "bastard", "cunt", "dick", "piss", "cock", "whore",
        "fag", "retard", "bloody", "bollocks", "wanker", "prick"
    ));

    // funny messages that rotate when bad word is detected
    // these are NOT corny i promise
    static final String[] SNITCH_MSGS = {
        "sir this is a wendy's",
        "bro... really? 💀",
        "POV: ur teacher overheard you",
        "mother of pearl!!",
        "your grandma is disappointed",
        "the wifi router can HEAR YOU",
        "adding this to ur permanent record",
        "FBI has entered the chat",
        "oh? OH?? OH!!!",
        "bro fell off 📉"
    };
    static int snitchMsgIdx = 0;

    static final Color C_BG       = new Color(13, 13, 23);
    static final Color C_CARD     = new Color(22, 22, 38);
    static final Color C_CARD2    = new Color(26, 26, 44);
    static final Color C_BORDER   = new Color(45, 45, 68);
    static final Color C_BLUE     = new Color(82, 168, 255);
    static final Color C_BLUE_DIM = new Color(42, 90, 145);
    static final Color C_GREEN    = new Color(52, 211, 105);
    static final Color C_RED      = new Color(255, 75, 75);
    static final Color C_YELLOW   = new Color(255, 212, 55);
    static final Color C_ORANGE   = new Color(255, 148, 35);
    static final Color C_PURPLE   = new Color(168, 118, 255);
    static final Color C_TEAL     = new Color(52, 200, 190);
    static final Color C_TEXT     = new Color(225, 225, 242);
    static final Color C_SUBTEXT  = new Color(118, 118, 148);
    static final Color C_MUTED    = new Color(68, 68, 95);

    public DeafMode(AudioStuff audioIn, AlertManager alertsIn, UserProfileManager profilesIn) {
        audio    = audioIn;
        alerts   = alertsIn;
        profiles = profilesIn;

        if (profiles.getCurrentUser() != null) {
            threshCorn = profiles.getCurrentUser().thresholdCorn;
        }

        setBackground(C_BG);
        setLayout(new BorderLayout(0, 0));

        add(makeTopBar(),    BorderLayout.NORTH);
        add(makeSplitBody(), BorderLayout.CENTER);
        add(makeBottomBar(), BorderLayout.SOUTH);

        hookUpAudio();
        startSimTimer();

        System.out.println("[DeafMode] ready, STT + bad word detection loaded");
    }

    JPanel makeTopBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            BorderFactory.createEmptyBorder(12, 20, 12, 20)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(C_CARD);
        JLabel icon = new JLabel("🔊");
        icon.setFont(new Font("Arial", Font.PLAIN, 20));
        JLabel title = new JLabel("  Deaf Mode  —  Audio Monitor");
        title.setFont(new Font("Arial", Font.BOLD, 18));
        title.setForeground(C_BLUE);
        left.add(icon);
        left.add(title);

        statusLbl = new JLabel("● Listening");
        statusLbl.setFont(new Font("Arial", Font.BOLD, 12));
        statusLbl.setForeground(C_GREEN);
        // pill-style background
        statusLbl.setOpaque(true);
        statusLbl.setBackground(new Color(52, 211, 105, 28));
        statusLbl.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(52, 211, 105, 60), 1),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        ));

        p.add(left,      BorderLayout.WEST);
        p.add(statusLbl, BorderLayout.EAST);
        return p;
    }

    JPanel makeSplitBody() {
        JPanel split = new JPanel(new GridLayout(1, 2, 1, 0));
        split.setBackground(C_BORDER); // the 1px divider between panels
        split.add(makeAudioPanel());
        split.add(makeSTTPanel());
        return split;
    }

    JPanel makeAudioPanel() {
        JPanel p = new JPanel();
        p.setBackground(C_BG);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(18, 18, 10, 14));

        addSectionHeader(p, "Audio Levels");
        p.add(Box.createVerticalStrut(12));

        // current level label + number
        JPanel lvlRow = new JPanel(new BorderLayout());
        lvlRow.setBackground(C_BG);
        lvlRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        lvlRow.setMaximumSize(new Dimension(99999, 20));
        JLabel lvlTitle = makeSmallLabel("Current Level");
        levelNumLbl = makeSmallLabel("0%");
        levelNumLbl.setForeground(C_TEAL);
        levelNumLbl.setFont(new Font("Arial", Font.BOLD, 12));
        lvlRow.add(lvlTitle, BorderLayout.WEST);
        lvlRow.add(levelNumLbl, BorderLayout.EAST);
        p.add(lvlRow);
        p.add(Box.createVerticalStrut(5));

        volBar = makeBar(C_GREEN, 38);
        p.add(volBar);
        p.add(Box.createVerticalStrut(14));

        // peak
        addSmallLbl(p, "Peak Level");
        p.add(Box.createVerticalStrut(5));
        peakBar = makeBar(C_YELLOW, 24);
        p.add(peakBar);
        p.add(Box.createVerticalStrut(20));

        addSectionHeader(p, "Alert Threshold");
        p.add(Box.createVerticalStrut(8));

        threshSlider = new JSlider(10, 100, threshCorn);
        threshSlider.setBackground(C_BG);
        threshSlider.setForeground(C_SUBTEXT);
        threshSlider.setMajorTickSpacing(15);
        threshSlider.setMinorTickSpacing(5);
        threshSlider.setPaintTicks(true);
        threshSlider.setPaintLabels(true);
        threshSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        threshSlider.setMaximumSize(new Dimension(99999, 55));
        threshSlider.addChangeListener(e -> {
            threshCorn = threshSlider.getValue();
            threshDescLbl.setText(describeThresh(threshCorn));
            if (profiles.getCurrentUser() != null)
                profiles.getCurrentUser().thresholdCorn = threshCorn;
        });
        p.add(threshSlider);
        p.add(Box.createVerticalStrut(4));

        threshDescLbl = makeSmallLabel(describeThresh(threshCorn));
        threshDescLbl.setForeground(C_YELLOW);
        p.add(threshDescLbl);
        p.add(Box.createVerticalStrut(22));

        addSectionHeader(p, "Vibe Meter");
        p.add(Box.createVerticalStrut(10));

        // giant emoji
        vibeEmojiLbl = new JLabel("😶", SwingConstants.CENTER);
        vibeEmojiLbl.setFont(new Font("Dialog", Font.PLAIN, 44));
        vibeEmojiLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        vibeEmojiLbl.setMaximumSize(new Dimension(99999, 60));
        p.add(vibeEmojiLbl);
        p.add(Box.createVerticalStrut(6));

        vibeLbl = new JLabel("Calculating vibes...", SwingConstants.LEFT);
        vibeLbl.setFont(new Font("Arial", Font.BOLD, 15));
        vibeLbl.setForeground(C_TEXT);
        vibeLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(vibeLbl);
        p.add(Box.createVerticalStrut(5));

        soundDescLbl = makeSmallLabel("computing...");
        soundDescLbl.setForeground(C_SUBTEXT);
        p.add(soundDescLbl);

        p.add(Box.createVerticalGlue());
        return p;
    }

    JPanel makeSTTPanel() {
        JPanel p = new JPanel();
        p.setBackground(C_BG);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(18, 14, 10, 18));

        addSectionHeader(p, "Speech to Text");
        p.add(Box.createVerticalStrut(10));

        // listen button + status in a row
        JPanel listenRow = new JPanel(new BorderLayout(10, 0));
        listenRow.setBackground(C_BG);
        listenRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        listenRow.setMaximumSize(new Dimension(99999, 44));

        listenBtn = new JButton("🎤  Listen");
        listenBtn.setBackground(C_GREEN);
        listenBtn.setForeground(new Color(10, 10, 10));
        listenBtn.setFont(new Font("Arial", Font.BOLD, 14));
        listenBtn.setFocusPainted(false);
        listenBtn.setBorderPainted(false);
        listenBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        listenBtn.setPreferredSize(new Dimension(140, 38));
        listenBtn.addActionListener(e -> startSTT());

        sttStatusLbl = new JLabel("Click Listen to transcribe speech");
        sttStatusLbl.setFont(new Font("Arial", Font.ITALIC, 11));
        sttStatusLbl.setForeground(C_SUBTEXT);

        listenRow.add(listenBtn,   BorderLayout.WEST);
        listenRow.add(sttStatusLbl, BorderLayout.CENTER);
        p.add(listenRow);
        p.add(Box.createVerticalStrut(10));

        // requirements note
        JLabel reqNote = makeSmallLabel("Requires: pip3 install SpeechRecognition pyaudio");
        reqNote.setForeground(C_MUTED);
        p.add(reqNote);
        p.add(Box.createVerticalStrut(12));

        // transcription text area (chat bubble style-ish)
        transcriptArea = new JTextArea();
        transcriptArea.setBackground(C_CARD);
        transcriptArea.setForeground(C_TEXT);
        transcriptArea.setFont(new Font("Arial", Font.PLAIN, 13));
        transcriptArea.setEditable(false);
        transcriptArea.setLineWrap(true);
        transcriptArea.setWrapStyleWord(true);
        transcriptArea.setText("speech will appear here...\n\n");
        transcriptArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JScrollPane scrolly = new JScrollPane(transcriptArea);
        scrolly.setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        scrolly.setBackground(C_CARD);
        scrolly.getViewport().setBackground(C_CARD);
        scrolly.setAlignmentX(Component.LEFT_ALIGNMENT);
        scrolly.setMaximumSize(new Dimension(99999, 170));
        scrolly.setPreferredSize(new Dimension(999, 170));
        p.add(scrolly);
        p.add(Box.createVerticalStrut(18));

        addSectionHeader(p, "Snitch Mode 🚨");
        p.add(Box.createVerticalStrut(10));

        // the alert box
        badWordAlertPanel = new JPanel(new BorderLayout(0, 4));
        badWordAlertPanel.setBackground(new Color(35, 18, 18));
        badWordAlertPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 75, 75, 60), 1),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ));
        badWordAlertPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        badWordAlertPanel.setMaximumSize(new Dimension(99999, 90));

        JLabel snitchTitle = new JLabel("🚨  Language Monitor Active");
        snitchTitle.setFont(new Font("Arial", Font.BOLD, 12));
        snitchTitle.setForeground(new Color(255, 100, 100));

        badWordAlertLbl = new JLabel("No bad words detected... yet");
        badWordAlertLbl.setFont(new Font("Arial", Font.PLAIN, 12));
        badWordAlertLbl.setForeground(C_SUBTEXT);

        funnyMsgLbl = new JLabel(" ");
        funnyMsgLbl.setFont(new Font("Arial", Font.ITALIC, 11));
        funnyMsgLbl.setForeground(C_MUTED);

        badWordAlertPanel.add(snitchTitle,    BorderLayout.NORTH);
        badWordAlertPanel.add(badWordAlertLbl, BorderLayout.CENTER);
        badWordAlertPanel.add(funnyMsgLbl,    BorderLayout.SOUTH);
        p.add(badWordAlertPanel);
        p.add(Box.createVerticalStrut(10));

        // cuss counter row
        JPanel cussRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        cussRow.setBackground(C_BG);
        cussRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        cussRow.setMaximumSize(new Dimension(99999, 28));

        cussCountLbl = new JLabel("Cuss Count: 0");
        cussCountLbl.setFont(new Font("Arial", Font.BOLD, 13));
        cussCountLbl.setForeground(C_SUBTEXT);
        cussCountLbl.setOpaque(true);
        cussCountLbl.setBackground(C_CARD);
        cussCountLbl.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)
        ));

        cussRow.add(cussCountLbl);
        p.add(cussRow);
        p.add(Box.createVerticalGlue());
        return p;
    }

    JPanel makeBottomBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        p.setBackground(C_CARD);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));

        p.add(makeBtn("🔔  Test Alert",   C_YELLOW,  new Color(10,10,10), e -> doTestAlert()));
        p.add(makeBtn("↩  Undo",          C_CARD2,   C_TEXT,              e -> doUndo()));
        p.add(makeBtn("🗑  Clear Log",     C_CARD2,   C_TEXT,              e -> transcriptArea.setText("")));
        p.add(makeBtn("🧹  Reset Count",   C_CARD2,   C_TEXT,              e -> {
            cussCount = 0;
            cussCountLbl.setText("Cuss Count: 0");
            cussCountLbl.setForeground(C_SUBTEXT);
            badWordAlertLbl.setText("No bad words detected... yet");
            funnyMsgLbl.setText(" ");
        }));

        JLabel tipLbl = new JLabel("  tip: needs internet for STT  |  pip3 install SpeechRecognition pyaudio");
        tipLbl.setForeground(C_MUTED);
        tipLbl.setFont(new Font("Arial", Font.ITALIC, 10));
        p.add(tipLbl);

        return p;
    }

    void hookUpAudio() {
        if (audio != null) {
            audio.addListener((level, peak) ->
                SwingUtilities.invokeLater(() -> refreshDisplay(level, peak))
            );
        }
    }

    void refreshDisplay(double level, double peak) {
        int lvPct = Math.max(0, Math.min(100, (int)(level * 100)));
        int pkPct = Math.max(0, Math.min(100, (int)(peak  * 100)));

        volBar.setValue(lvPct);
        volBar.setString(lvPct + "%");
        peakBar.setValue(pkPct);
        peakBar.setString("Peak " + pkPct + "%");
        levelNumLbl.setText(lvPct + "%");

        // color the bar
        if (lvPct >= threshCorn) {
            volBar.setForeground(C_RED);
            doFireAlert(lvPct);
        } else if (lvPct >= threshCorn * 0.78) {
            volBar.setForeground(C_ORANGE);
        } else if (lvPct >= threshCorn * 0.5) {
            volBar.setForeground(C_YELLOW);
        } else {
            volBar.setForeground(C_GREEN);
        }

        // smooth average for vibe meter
        avgLevel = avgLevel * 0.92 + level * 0.08;
        updateVibeMeter((int)(avgLevel * 100));
    }

    void updateVibeMeter(int avgPct) {
        // updates the vibe emoji + label + sound description
        // based on the rolling average audio level
        if (avgPct < 8) {
            vibeEmojiLbl.setText("💀");
            vibeLbl.setText("Dead Silent");
            vibeLbl.setForeground(C_SUBTEXT);
            soundDescLbl.setText("quieter than your social life rn");
        } else if (avgPct < 20) {
            vibeEmojiLbl.setText("😴");
            vibeLbl.setText("Barely Alive");
            vibeLbl.setForeground(C_SUBTEXT);
            soundDescLbl.setText("library quiet 📚");
        } else if (avgPct < 38) {
            vibeEmojiLbl.setText("😌");
            vibeLbl.setText("Chill");
            vibeLbl.setForeground(C_TEAL);
            soundDescLbl.setText("average classroom energy");
        } else if (avgPct < 55) {
            vibeEmojiLbl.setText("🔊");
            vibeLbl.setText("Getting Loud");
            vibeLbl.setForeground(C_YELLOW);
            soundDescLbl.setText("something is going on out there");
        } else if (avgPct < 72) {
            vibeEmojiLbl.setText("🔥");
            vibeLbl.setText("Lit");
            vibeLbl.setForeground(C_ORANGE);
            soundDescLbl.setText("your mom is yelling again 📢");
        } else if (avgPct < 88) {
            vibeEmojiLbl.setText("🎸");
            vibeLbl.setText("Concert Mode");
            vibeLbl.setForeground(C_RED);
            soundDescLbl.setText("Nickelback front row energy");
        } else {
            vibeEmojiLbl.setText("🛸");
            vibeLbl.setText("SEND HELP");
            vibeLbl.setForeground(C_RED);
            soundDescLbl.setText("standing next to a jet engine 🛫");
        }
    }

    void doFireAlert(int lvPct) {
        if (alertGoingOn) return;
        alertGoingOn = true;

        String msg = (lvPct >= 90) ? "EXTREME noise!! (" + lvPct + "%)"
                   : (lvPct >= 78) ? "Loud noise (" + lvPct + "%)"
                   : "Sound above threshold (" + lvPct + "%)";
        int sev = (lvPct >= 90) ? 3 : (lvPct >= 78) ? 2 : 1;

        alerts.addAlert(new Alert(Alert.AlertType.DEAF_AUDIO, msg, sev, "audio"));

        statusLbl.setText("⚠  ALERT: " + msg);
        statusLbl.setForeground(C_RED);
        statusLbl.setBackground(new Color(255, 75, 75, 28));
        statusLbl.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 75, 75, 80), 1),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        ));

        // flash the top bar panel
        if (flashTimer != null) flashTimer.stop();
        flashCob = 0;
        final JPanel topBar = (JPanel) getComponent(0);
        flashTimer = new javax.swing.Timer(220, e -> {
            flashCob++;
            Color flashCol = (sev == 3) ? C_RED : (sev == 2) ? C_ORANGE : C_YELLOW;
            topBar.setBackground((flashCob % 2 == 0) ? new Color(flashCol.getRed(), flashCol.getGreen(), flashCol.getBlue(), 38) : C_CARD);
        });
        flashTimer.start();

        AudioStuff.speak(msg);

        javax.swing.Timer reset = new javax.swing.Timer(2500, e -> {
            alertGoingOn = false;
            statusLbl.setText("● Listening");
            statusLbl.setForeground(C_GREEN);
            statusLbl.setBackground(new Color(52, 211, 105, 28));
            statusLbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(52, 211, 105, 60), 1),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)
            ));
            if (flashTimer != null) flashTimer.stop();
            topBar.setBackground(C_CARD);
        });
        reset.setRepeats(false);
        reset.start();
    }

    void startSTT() {
        if (sttActive) {
            // already listening, tell user
            sttStatusLbl.setText("Already listening!! wait for it to finish");
            return;
        }
        sttActive = true;

        listenBtn.setText("🔴  Listening...");
        listenBtn.setBackground(C_RED);
        listenBtn.setForeground(Color.WHITE);
        sttStatusLbl.setText("Speak now, up to ~15 seconds");
        sttStatusLbl.setForeground(C_RED);
        transcriptArea.append("🎤 Listening...\n");

        // stop java audio while python uses mic
        // (on mac they can share but this is safer)
        if (audio != null) audio.stopListening();

        Thread sttThread = new Thread(() -> {
            String result = runPythonSTT();

            SwingUtilities.invokeLater(() -> {
                processSTTResult(result);

                // restart java audio
                if (audio != null) audio.startListening();

                sttActive = false;
                listenBtn.setText("🎤  Listen");
                listenBtn.setBackground(C_GREEN);
                listenBtn.setForeground(new Color(10, 10, 10));
                sttStatusLbl.setText("Click Listen to transcribe speech");
                sttStatusLbl.setForeground(C_SUBTEXT);
            });
        });
        sttThread.setDaemon(true);
        sttThread.setName("STTThread");
        sttThread.start();
    }

    // writes + runs the python STT script
    String runPythonSTT() {
        // the python script as a string
        // uses googles free speech api (needs internet)
        String script =
            "import sys\n" +
            "try:\n" +
            "    import speech_recognition as sr\n" +
            "    r = sr.Recognizer()\n" +
            "    r.pause_threshold = 1.0\n" +
            "    r.dynamic_energy_threshold = True\n" +
            "    with sr.Microphone() as source:\n" +
            "        r.adjust_for_ambient_noise(source, duration=0.5)\n" +
            "        audio = r.listen(source, timeout=12, phrase_time_limit=15)\n" +
            "    try:\n" +
            "        print(r.recognize_google(audio))\n" +
            "    except sr.UnknownValueError:\n" +
            "        print('__UNCLEAR__')\n" +
            "    except sr.RequestError:\n" +
            "        print('__NO_NET__')\n" +
            "except ImportError:\n" +
            "    print('__NO_LIB__')\n" +
            "except Exception as e:\n" +
            "    print('__ERR__')\n";

        try {
            // write python script to temp file
            File scriptFile = new File("/tmp/aa_stt.py");
            PrintWriter pw = new PrintWriter(new FileWriter(scriptFile));
            pw.print(script);
            pw.close();

            // run it
            Process p = Runtime.getRuntime().exec(new String[]{"python3", "/tmp/aa_stt.py"});

            // read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            reader.close();

            return (line != null) ? line.trim() : "__SILENT__";

        } catch (Exception glonk) {
            System.out.println("[STT] error running python: " + glonk.getMessage());
            return "__ERR__";
        }
    }

    // process the result string from python
    void processSTTResult(String result) {
        System.out.println("[STT] result: " + result);

        if (result == null || result.isEmpty() || "__SILENT__".equals(result)) {
            transcriptArea.append("🤫 (silence detected)\n\n");
            return;
        }
        if ("__UNCLEAR__".equals(result)) {
            transcriptArea.append("🤷 Couldn't understand that, speak more clearly\n\n");
            return;
        }
        if ("__NO_NET__".equals(result)) {
            transcriptArea.append("📵 No internet connection for speech recognition\n\n");
            return;
        }
        if ("__NO_LIB__".equals(result)) {
            transcriptArea.append("❌ speech_recognition not installed!\n");
            transcriptArea.append("   Run: pip3 install SpeechRecognition pyaudio\n\n");
            sttStatusLbl.setText("Install: pip3 install SpeechRecognition pyaudio");
            sttStatusLbl.setForeground(C_RED);
            return;
        }
        if (result.startsWith("__ERR__")) {
            transcriptArea.append("❓ Something went wrong (check terminal for details)\n\n");
            return;
        }

        // we got actual transcribed text!!
        String lower = result.toLowerCase();

        // check for bad words
        List<String> foundBadWords = new ArrayList<>();
        String displayResult = result; // start with original

        for (String bw : BAD_WORDS) {
            // check if this bad word appears in the text
            if (lower.contains(bw)) {
                foundBadWords.add(bw);
                // censor it - replace with first letter + asterisks + last letter
                String censored = censorWord(bw);
                // case insensitive replace
                displayResult = displayResult.replaceAll("(?i)" + bw, censored);
            }
        }

        // show the text (censored if needed)
        transcriptArea.append("💬 \"" + displayResult + "\"\n\n");

        // scroll to bottom
        transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());

        // store as alert
        alerts.addAlert(new Alert(Alert.AlertType.SYSTEM, "Heard: " + displayResult, 1, "speech"));

        // if bad words found, trigger snitch mode
        if (!foundBadWords.isEmpty()) {
            triggerSnitchMode(foundBadWords, displayResult);
        }
    }

    void triggerSnitchMode(List<String> words, String censoredText) {
        cussCount += words.size();
        cussCountLbl.setText("🚨 Cuss Count: " + cussCount);
        cussCountLbl.setForeground(C_RED);

        // show what was caught (censored)
        String wordList = String.join(", ", new ArrayList<String>() {{
            for (String w : words) add(censorWord(w));
        }});
        badWordAlertLbl.setText("Caught: " + wordList);
        badWordAlertLbl.setForeground(C_RED);

        // rotate through funny messages
        funnyMsgLbl.setText(SNITCH_MSGS[snitchMsgIdx % SNITCH_MSGS.length]);
        snitchMsgIdx++;
        funnyMsgLbl.setForeground(C_ORANGE);

        // flash the snitch panel background red
        badWordAlertPanel.setBackground(new Color(80, 18, 18));
        badWordAlertPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_RED, 2),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ));

        // TTS alert for people nearby
        AudioStuff.speak("Language alert detected.");

        // add to main alerts
        alerts.addAlert(new Alert(Alert.AlertType.DEAF_URGENT,
            "Bad word detected: " + wordList, 2, "language"));

        // reset alert box visuals after 5 seconds
        javax.swing.Timer reset = new javax.swing.Timer(5000, e -> {
            badWordAlertPanel.setBackground(new Color(35, 18, 18));
            badWordAlertPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 75, 75, 60), 1),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)
            ));
            badWordAlertLbl.setForeground(C_SUBTEXT);
        });
        reset.setRepeats(false);
        reset.start();
    }

    // censors a word: "fuck" → "f**k"
    String censorWord(String word) {
        if (word == null || word.isEmpty()) return "*";
        if (word.length() == 1) return "*";
        if (word.length() == 2) return word.charAt(0) + "*";
        // keep first + last letter, fill middle with *
        char[] stars = new char[word.length() - 2];
        Arrays.fill(stars, '*');
        return word.charAt(0) + new String(stars) + word.charAt(word.length() - 1);
    }

    void startSimTimer() {
        simTimer = new javax.swing.Timer(85, e -> {
            if (audio == null || !audio.isRunning()) {
                double fakeLv = AudioStuff.simulateLevel();
                refreshDisplay(fakeLv, fakeLv * 1.04);
            }
        });
        simTimer.start();
    }

    void doTestAlert() {
        Alert ta = new Alert(Alert.AlertType.DEAF_AUDIO, "Test alert — system working!", 1, "test");
        alerts.addAlert(ta);
        transcriptArea.append("✅ Test alert fired — system is working!\n\n");
        AudioStuff.speak("Test alert. Deaf mode is working correctly.");
    }

    void doUndo() {
        String undone = alerts.undoLast();
        statusLbl.setText(undone != null ? "↩ Undid: " + undone : "Nothing to undo");
        statusLbl.setForeground(undone != null ? C_BLUE : C_SUBTEXT);
    }

    String describeThresh(int t) {
        if (t < 30) return "Very sensitive — almost anything triggers";
        if (t < 50) return "Sensitive";
        if (t < 70) return "Normal";
        if (t < 85) return "Lenient";
        return "Only very loud sounds";
    }

    void addSectionHeader(JPanel p, String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(new Font("Arial", Font.BOLD, 10));
        l.setForeground(C_SUBTEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        // underline with border
        l.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            BorderFactory.createEmptyBorder(0, 0, 6, 0)
        ));
        l.setMaximumSize(new Dimension(99999, 24));
        p.add(l);
    }

    void addSmallLbl(JPanel p, String text) {
        JLabel l = makeSmallLabel(text);
        p.add(l);
    }

    JLabel makeSmallLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Arial", Font.PLAIN, 11));
        l.setForeground(C_SUBTEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    JProgressBar makeBar(Color fg, int h) {
        JProgressBar b = new JProgressBar(0, 100);
        b.setValue(0);
        b.setStringPainted(true);
        b.setString("0%");
        b.setForeground(fg);
        b.setBackground(new Color(38, 38, 58));
        b.setMaximumSize(new Dimension(99999, h));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setFont(new Font("Arial", Font.BOLD, 11));
        return b;
    }

    JButton makeBtn(String text, Color bg, Color fg, ActionListener a) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFont(new Font("Arial", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(a);
        return b;
    }

    public void cleanup() {
        if (simTimer   != null) simTimer.stop();
        if (flashTimer != null) flashTimer.stop();
        System.out.println("[DeafMode] cleaned up");
    }
}
