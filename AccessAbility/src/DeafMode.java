import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

// DeafMode.java — audio monitoring + speech-to-text panel
// listens to mic, shows visual alerts when sounds get loud
// STT uses whisper (local, no internet!) with google as fallback
// NOTE: pip3 install openai-whisper SpeechRecognition pyaudio

public class DeafMode extends JPanel {

    AudioStuff         audio;
    AlertManager       alerts;
    UserProfileManager profiles;

    JProgressBar volBar, peakBar;
    JSlider      threshSlider;
    JLabel       threshDescLbl, vibeLbl, vibeEmojiLbl, soundDescLbl, levelNumLbl;
    JButton      listenBtn;
    JLabel       sttStatusLbl;
    JTextArea    transcriptArea;
    JPanel       badWordAlertPanel;
    JLabel       badWordAlertLbl, cussCountLbl, funnyMsgLbl, statusLbl;

    int     threshCorn   = 65;
    boolean alertGoingOn = false, sttActive = false;
    int     cussCount    = 0;
    // persistent whisper process — model loads once so its fast after first use
    Process        sttProc   = null;
    BufferedWriter sttStdin  = null;
    BufferedReader sttStdout = null;
    boolean        whisperOk = false;
    javax.swing.Timer simTimer, flashTimer;
    int     flashCob     = 0;
    double  avgLevel     = 0.0;

    // profanity filter — removed hell/damn/ass/dick bc too many false positives
    // (hello, damn good, class, dictionary, Patrick — all got flagged)
    static final Set<String> BAD_WORDS = new HashSet<>(Arrays.asList(
        "fuck", "shit", "bitch", "crap", "bastard", "cunt", "piss", "cock", "whore", "fag", "retard", "bollocks", "wanker"
    ));
    static final String[] SNITCH_MSGS = {"watch your language!", "bro really? 💀", "your grandma is disappointed", "adding this to ur permanent record"};
    static int snitchMsgIdx = 0;

    static final Color C_BG = new Color(13,13,23), C_CARD = new Color(22,22,38), C_CARD2 = new Color(26,26,44), C_BORDER = new Color(45,45,68);
    static final Color C_BLUE = new Color(82,168,255), C_BLUE_DIM = new Color(42,90,145), C_GREEN = new Color(52,211,105);
    static final Color C_RED = new Color(255,75,75), C_YELLOW = new Color(255,212,55), C_ORANGE = new Color(255,148,35);
    static final Color C_PURPLE = new Color(168,118,255), C_TEAL = new Color(52,200,190);
    static final Color C_TEXT = new Color(225,225,242), C_SUBTEXT = new Color(118,118,148), C_MUTED = new Color(68,68,95);

    public DeafMode(AudioStuff audioIn, AlertManager alertsIn, UserProfileManager profilesIn) {
        audio = audioIn; alerts = alertsIn; profiles = profilesIn;
        if (profiles.getCurrentUser() != null) threshCorn = profiles.getCurrentUser().thresholdCorn;
        setBackground(C_BG); setLayout(new BorderLayout(0, 0));
        add(makeTopBar(),    BorderLayout.NORTH);
        add(makeSplitBody(), BorderLayout.CENTER);
        add(makeBottomBar(), BorderLayout.SOUTH);
        hookUpAudio(); startSimTimer();
        // start whisper process in background so its ready by the time user clicks listen
        new Thread(() -> startSttProcess()).start();
        System.out.println("[DeafMode] ready, STT + bad word detection loaded");
    }

    JPanel makeTopBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER), BorderFactory.createEmptyBorder(12,20,12,20)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); left.setBackground(C_CARD);
        JLabel icon = new JLabel("🔊"); icon.setFont(new Font("Arial", Font.PLAIN, 20));
        JLabel title = new JLabel("  Deaf Mode  —  Audio Monitor"); title.setFont(new Font("Arial", Font.BOLD, 18)); title.setForeground(C_BLUE);
        left.add(icon); left.add(title);
        statusLbl = new JLabel("● Listening"); statusLbl.setFont(new Font("Arial", Font.BOLD, 12)); statusLbl.setForeground(C_GREEN);
        statusLbl.setOpaque(true); statusLbl.setBackground(new Color(52,211,105,28));
        statusLbl.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(52,211,105,60),1), BorderFactory.createEmptyBorder(4,12,4,12)));
        p.add(left, BorderLayout.WEST); p.add(statusLbl, BorderLayout.EAST); return p;
    }

    JPanel makeSplitBody() {
        JPanel split = new JPanel(new GridLayout(1, 2, 1, 0));
        split.setBackground(C_BORDER); // 1px divider
        split.add(makeAudioPanel()); split.add(makeSTTPanel()); return split;
    }

    JPanel makeAudioPanel() {
        JPanel p = new JPanel(); p.setBackground(C_BG); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(18, 18, 10, 14));
        addHdr(p, "Audio Levels"); p.add(Box.createVerticalStrut(12));

        // current level row
        JPanel lvlRow = new JPanel(new BorderLayout()); lvlRow.setBackground(C_BG);
        lvlRow.setAlignmentX(Component.LEFT_ALIGNMENT); lvlRow.setMaximumSize(new Dimension(99999, 20));
        JLabel lvlTitle = mkLbl("Current Level");
        levelNumLbl = mkLbl("0%"); levelNumLbl.setForeground(C_TEAL); levelNumLbl.setFont(new Font("Arial", Font.BOLD, 12));
        lvlRow.add(lvlTitle, BorderLayout.WEST); lvlRow.add(levelNumLbl, BorderLayout.EAST);
        p.add(lvlRow); p.add(Box.createVerticalStrut(5));
        volBar = makeBar(C_GREEN, 38); p.add(volBar); p.add(Box.createVerticalStrut(14));
        p.add(mkLbl("Peak Level")); p.add(Box.createVerticalStrut(5));
        peakBar = makeBar(C_YELLOW, 24); p.add(peakBar); p.add(Box.createVerticalStrut(20));

        addHdr(p, "Alert Threshold"); p.add(Box.createVerticalStrut(8));
        threshSlider = new JSlider(10, 100, threshCorn);
        threshSlider.setBackground(C_BG); threshSlider.setForeground(C_SUBTEXT);
        threshSlider.setMajorTickSpacing(15); threshSlider.setMinorTickSpacing(5);
        threshSlider.setPaintTicks(true); threshSlider.setPaintLabels(true);
        threshSlider.setAlignmentX(Component.LEFT_ALIGNMENT); threshSlider.setMaximumSize(new Dimension(99999, 55));
        threshSlider.addChangeListener(e -> {
            threshCorn = threshSlider.getValue(); threshDescLbl.setText(describeThresh(threshCorn));
            if (profiles.getCurrentUser() != null) profiles.getCurrentUser().thresholdCorn = threshCorn;
        });
        p.add(threshSlider); p.add(Box.createVerticalStrut(4));
        threshDescLbl = mkLbl(describeThresh(threshCorn)); threshDescLbl.setForeground(C_YELLOW);
        p.add(threshDescLbl); p.add(Box.createVerticalStrut(22));

        addHdr(p, "Vibe Meter"); p.add(Box.createVerticalStrut(10));
        vibeEmojiLbl = new JLabel("😶", SwingConstants.CENTER); vibeEmojiLbl.setFont(new Font("Dialog", Font.PLAIN, 44));
        vibeEmojiLbl.setAlignmentX(Component.LEFT_ALIGNMENT); vibeEmojiLbl.setMaximumSize(new Dimension(99999, 60));
        p.add(vibeEmojiLbl); p.add(Box.createVerticalStrut(6));
        vibeLbl = new JLabel("Calculating vibes...", SwingConstants.LEFT); vibeLbl.setFont(new Font("Arial", Font.BOLD, 15));
        vibeLbl.setForeground(C_TEXT); vibeLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(vibeLbl); p.add(Box.createVerticalStrut(5));
        soundDescLbl = mkLbl("computing..."); soundDescLbl.setForeground(C_SUBTEXT); p.add(soundDescLbl);
        p.add(Box.createVerticalGlue()); return p;
    }

    JPanel makeSTTPanel() {
        JPanel p = new JPanel(); p.setBackground(C_BG); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(18, 14, 10, 18));
        addHdr(p, "Speech to Text"); p.add(Box.createVerticalStrut(10));

        JPanel listenRow = new JPanel(new BorderLayout(10, 0)); listenRow.setBackground(C_BG);
        listenRow.setAlignmentX(Component.LEFT_ALIGNMENT); listenRow.setMaximumSize(new Dimension(99999, 44));
        listenBtn = new JButton("🎤  Listen"); listenBtn.setBackground(C_GREEN); listenBtn.setForeground(new Color(10,10,10));
        listenBtn.setOpaque(true); // without this the text is invisible on mac lol
        listenBtn.setFont(new Font("Arial", Font.BOLD, 14)); listenBtn.setFocusPainted(false); listenBtn.setBorderPainted(false);
        listenBtn.setCursor(new Cursor(Cursor.HAND_CURSOR)); listenBtn.setPreferredSize(new Dimension(140, 38)); listenBtn.addActionListener(e -> startSTT());
        sttStatusLbl = new JLabel("Click Listen to transcribe speech"); sttStatusLbl.setFont(new Font("Arial", Font.ITALIC, 11)); sttStatusLbl.setForeground(C_SUBTEXT);
        listenRow.add(listenBtn, BorderLayout.WEST); listenRow.add(sttStatusLbl, BorderLayout.CENTER);
        p.add(listenRow); p.add(Box.createVerticalStrut(10));
        JLabel reqNote = mkLbl("Requires: pip3 install SpeechRecognition pyaudio"); reqNote.setForeground(C_MUTED);
        p.add(reqNote); p.add(Box.createVerticalStrut(12));

        transcriptArea = new JTextArea(); transcriptArea.setBackground(C_CARD); transcriptArea.setForeground(C_TEXT);
        transcriptArea.setFont(new Font("Arial", Font.PLAIN, 13)); transcriptArea.setEditable(false);
        transcriptArea.setLineWrap(true); transcriptArea.setWrapStyleWord(true); transcriptArea.setText("speech will appear here...\n\n");
        transcriptArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scrolly = new JScrollPane(transcriptArea); scrolly.setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        scrolly.setBackground(C_CARD); scrolly.getViewport().setBackground(C_CARD);
        scrolly.setAlignmentX(Component.LEFT_ALIGNMENT); scrolly.setMaximumSize(new Dimension(99999, 170)); scrolly.setPreferredSize(new Dimension(999, 170));
        p.add(scrolly); p.add(Box.createVerticalStrut(18));

        addHdr(p, "Snitch Mode 🚨"); p.add(Box.createVerticalStrut(10));
        badWordAlertPanel = new JPanel(new BorderLayout(0, 4)); badWordAlertPanel.setBackground(new Color(35,18,18));
        badWordAlertPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(255,75,75,60),1), BorderFactory.createEmptyBorder(12,14,12,14)));
        badWordAlertPanel.setAlignmentX(Component.LEFT_ALIGNMENT); badWordAlertPanel.setMaximumSize(new Dimension(99999, 90));
        JLabel snitchTitle = new JLabel("🚨  Language Monitor Active"); snitchTitle.setFont(new Font("Arial", Font.BOLD, 12)); snitchTitle.setForeground(new Color(255,100,100));
        badWordAlertLbl = new JLabel("No bad words detected... yet"); badWordAlertLbl.setFont(new Font("Arial", Font.PLAIN, 12)); badWordAlertLbl.setForeground(C_SUBTEXT);
        funnyMsgLbl = new JLabel(" "); funnyMsgLbl.setFont(new Font("Arial", Font.ITALIC, 11)); funnyMsgLbl.setForeground(C_MUTED);
        badWordAlertPanel.add(snitchTitle, BorderLayout.NORTH); badWordAlertPanel.add(badWordAlertLbl, BorderLayout.CENTER); badWordAlertPanel.add(funnyMsgLbl, BorderLayout.SOUTH);
        p.add(badWordAlertPanel); p.add(Box.createVerticalStrut(10));

        JPanel cussRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); cussRow.setBackground(C_BG);
        cussRow.setAlignmentX(Component.LEFT_ALIGNMENT); cussRow.setMaximumSize(new Dimension(99999, 28));
        cussCountLbl = new JLabel("Cuss Count: 0"); cussCountLbl.setFont(new Font("Arial", Font.BOLD, 13)); cussCountLbl.setForeground(C_SUBTEXT);
        cussCountLbl.setOpaque(true); cussCountLbl.setBackground(C_CARD);
        cussCountLbl.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_BORDER,1), BorderFactory.createEmptyBorder(3,10,3,10)));
        cussRow.add(cussCountLbl); p.add(cussRow); p.add(Box.createVerticalGlue()); return p;
    }

    JPanel makeBottomBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        p.setBackground(C_CARD); p.setBorder(BorderFactory.createMatteBorder(1,0,0,0,C_BORDER));
        p.add(makeBtn("🔔  Test Alert", C_YELLOW, new Color(10,10,10), e -> doTestAlert()));
        p.add(makeBtn("↩  Undo",        C_CARD2,  C_TEXT,              e -> doUndo()));
        p.add(makeBtn("🗑  Clear Log",   C_CARD2,  C_TEXT,              e -> transcriptArea.setText("")));
        p.add(makeBtn("🧹  Reset Count", C_CARD2,  C_TEXT, e -> {
            cussCount = 0; cussCountLbl.setText("Cuss Count: 0"); cussCountLbl.setForeground(C_SUBTEXT);
            badWordAlertLbl.setText("No bad words detected... yet"); funnyMsgLbl.setText(" ");
        }));
        JLabel tipLbl = new JLabel("  tip: needs internet for STT  |  pip3 install SpeechRecognition pyaudio");
        tipLbl.setForeground(C_MUTED); tipLbl.setFont(new Font("Arial", Font.ITALIC, 10));
        p.add(tipLbl); return p;
    }

    void hookUpAudio() {
        if (audio != null) audio.addListener((level, peak) -> SwingUtilities.invokeLater(() -> refreshDisplay(level, peak)));
    }

    void refreshDisplay(double level, double peak) {
        int lvPct = Math.max(0, Math.min(100, (int)(level * 100)));
        int pkPct = Math.max(0, Math.min(100, (int)(peak  * 100)));
        volBar.setValue(lvPct); volBar.setString(lvPct + "%");
        peakBar.setValue(pkPct); peakBar.setString("Peak " + pkPct + "%");
        levelNumLbl.setText(lvPct + "%");
        if      (lvPct >= threshCorn)        { volBar.setForeground(C_RED);    doFireAlert(lvPct); }
        else if (lvPct >= threshCorn * 0.78) { volBar.setForeground(C_ORANGE); }
        else if (lvPct >= threshCorn * 0.5)  { volBar.setForeground(C_YELLOW); }
        else                                 { volBar.setForeground(C_GREEN); }
        avgLevel = avgLevel * 0.92 + level * 0.08;
        updateVibeMeter((int)(avgLevel * 100));
    }

    void updateVibeMeter(int avgPct) {
        if      (avgPct < 8)  { vibeEmojiLbl.setText("💀"); vibeLbl.setText("Dead Silent");    vibeLbl.setForeground(C_SUBTEXT); soundDescLbl.setText("quieter than your social life rn"); }
        else if (avgPct < 20) { vibeEmojiLbl.setText("😴"); vibeLbl.setText("Barely Alive");   vibeLbl.setForeground(C_SUBTEXT); soundDescLbl.setText("library quiet 📚"); }
        else if (avgPct < 38) { vibeEmojiLbl.setText("😌"); vibeLbl.setText("Chill");           vibeLbl.setForeground(C_TEAL);    soundDescLbl.setText("average classroom energy"); }
        else if (avgPct < 55) { vibeEmojiLbl.setText("🔊"); vibeLbl.setText("Getting Loud");    vibeLbl.setForeground(C_YELLOW);  soundDescLbl.setText("something is going on out there"); }
        else if (avgPct < 72) { vibeEmojiLbl.setText("🔥"); vibeLbl.setText("Lit");             vibeLbl.setForeground(C_ORANGE);  soundDescLbl.setText("your mom is yelling again 📢"); }
        else if (avgPct < 88) { vibeEmojiLbl.setText("🎸"); vibeLbl.setText("Concert Mode");    vibeLbl.setForeground(C_RED);     soundDescLbl.setText("Nickelback front row energy"); }
        else                  { vibeEmojiLbl.setText("🛸"); vibeLbl.setText("SEND HELP");       vibeLbl.setForeground(C_RED);     soundDescLbl.setText("standing next to a jet engine 🛫"); }
    }

    void doFireAlert(int lvPct) {
        if (alertGoingOn) return;
        alertGoingOn = true;
        String msg = (lvPct >= 90) ? "EXTREME noise!! (" + lvPct + "%)" : (lvPct >= 78) ? "Loud noise (" + lvPct + "%)" : "Sound above threshold (" + lvPct + "%)";
        int sev = (lvPct >= 90) ? 3 : (lvPct >= 78) ? 2 : 1;
        alerts.addAlert(new Alert(Alert.AlertType.DEAF_AUDIO, msg, sev, "audio"));
        statusLbl.setText("⚠  ALERT: " + msg); statusLbl.setForeground(C_RED); statusLbl.setBackground(new Color(255,75,75,28));
        statusLbl.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(255,75,75,80),1), BorderFactory.createEmptyBorder(4,12,4,12)));
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
            alertGoingOn = false; statusLbl.setText("● Listening"); statusLbl.setForeground(C_GREEN);
            statusLbl.setBackground(new Color(52,211,105,28));
            statusLbl.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(52,211,105,60),1), BorderFactory.createEmptyBorder(4,12,4,12)));
            if (flashTimer != null) flashTimer.stop();
            topBar.setBackground(C_CARD);
        });
        reset.setRepeats(false); reset.start();
    }

    // starts the persistent whisper process — model loads once so after first click its fast
    void startSttProcess() {
        if (sttProc != null) return;
        String script =
            "import sys, os, tempfile\n" +
            "sys.stdout.reconfigure(line_buffering=True)\n" +
            "try:\n" +
            "    import whisper, speech_recognition as sr\n" +
            "    model = whisper.load_model('tiny.en')\n" +  // tiny.en = fast + english only
            "    r = sr.Recognizer()\n" +
            "    r.pause_threshold = 3.5\n" +        // wait 3.5s of silence before stopping
            "    r.non_speaking_duration = 0.8\n" +  // keep a bit of silence at the end
            "    r.dynamic_energy_threshold = False\n" + // dont auto-adjust — causes early cutoff
            "    r.energy_threshold = 300\n" +        // fixed sensitivity, works in most rooms
            "    sys.stdout.write('WHISPER_READY\\n'); sys.stdout.flush()\n" +
            "    while True:\n" +
            "        cmd = sys.stdin.readline().strip()\n" +
            "        if cmd != 'listen': break\n" +
            "        try:\n" +
            "            with sr.Microphone() as src:\n" +
            "                r.adjust_for_ambient_noise(src, duration=0.2)\n" +
            "                audio = r.listen(src, timeout=15, phrase_time_limit=45)\n" +
            "            tmp = tempfile.mktemp(suffix='.wav')\n" +
            "            with open(tmp,'wb') as f: f.write(audio.get_wav_data())\n" +
            "            res = model.transcribe(tmp, language='en')\n" +
            "            os.unlink(tmp)\n" +
            "            txt = res['text'].strip()\n" +
            "            sys.stdout.write((txt if txt else '__SILENT__') + '\\n'); sys.stdout.flush()\n" +
            "        except Exception as ex: sys.stdout.write('__ERR__\\n'); sys.stdout.flush()\n" +
            "except ImportError:\n" +  // whisper not installed — fall back to google
            "    try:\n" +
            "        import speech_recognition as sr\n" +
            "        r = sr.Recognizer()\n" +
            "        r.pause_threshold = 3.5\n" +
            "        r.non_speaking_duration = 0.8\n" +
            "        r.dynamic_energy_threshold = False\n" +
            "        r.energy_threshold = 300\n" +
            "        sys.stdout.write('GOOGLE_READY\\n'); sys.stdout.flush()\n" +
            "        while True:\n" +
            "            cmd = sys.stdin.readline().strip()\n" +
            "            if cmd != 'listen': break\n" +
            "            try:\n" +
            "                with sr.Microphone() as src:\n" +
            "                    r.adjust_for_ambient_noise(src, duration=0.2)\n" +
            "                    audio = r.listen(src, timeout=15, phrase_time_limit=45)\n" +
            "                sys.stdout.write(r.recognize_google(audio) + '\\n'); sys.stdout.flush()\n" +
            "            except sr.UnknownValueError: sys.stdout.write('__UNCLEAR__\\n'); sys.stdout.flush()\n" +
            "            except sr.RequestError:     sys.stdout.write('__NO_NET__\\n');  sys.stdout.flush()\n" +
            "            except Exception:           sys.stdout.write('__ERR__\\n');     sys.stdout.flush()\n" +
            "    except ImportError: sys.stdout.write('__NO_LIB__\\n'); sys.stdout.flush()\n" +
            "except Exception: sys.stdout.write('__ERR__\\n'); sys.stdout.flush()\n";
        try {
            File f = new File("/tmp/aa_stt.py");
            PrintWriter pw = new PrintWriter(new FileWriter(f)); pw.print(script); pw.close();
            sttProc   = Runtime.getRuntime().exec(new String[]{"python3", "/tmp/aa_stt.py"});
            sttStdin  = new BufferedWriter(new OutputStreamWriter(sttProc.getOutputStream()));
            sttStdout = new BufferedReader(new InputStreamReader(sttProc.getInputStream()));
            String ready = sttStdout.readLine();
            whisperOk = "WHISPER_READY".equals(ready);
            System.out.println("[STT] process ready — " + ready);
        } catch (Exception e) { System.out.println("[STT] failed to start: " + e.getMessage()); sttProc = null; }
    }

    void startSTT() {
        if (sttActive) { sttStatusLbl.setText("Already listening!! wait for it to finish"); return; }
        sttActive = true;
        listenBtn.setText("🔴  Listening..."); listenBtn.setBackground(C_RED); listenBtn.setForeground(Color.WHITE);
        String engine = whisperOk ? "Whisper AI — speak now" : "Google STT — speak now";
        sttStatusLbl.setText(engine); sttStatusLbl.setForeground(C_RED);
        transcriptArea.append("🎤 Listening" + (whisperOk ? " (Whisper AI)" : "") + "...\n");
        if (audio != null) audio.stopListening();
        Thread t = new Thread(() -> {
            String result = runSttQuery();
            SwingUtilities.invokeLater(() -> {
                processSTTResult(result);
                if (audio != null) audio.startListening();
                sttActive = false; listenBtn.setText("🎤  Listen"); listenBtn.setBackground(C_GREEN); listenBtn.setForeground(new Color(10,10,10));
                sttStatusLbl.setText("Click Listen to transcribe speech"); sttStatusLbl.setForeground(C_SUBTEXT);
            });
        });
        t.setDaemon(true); t.setName("STTThread"); t.start();
    }

    String runSttQuery() {
        if (sttProc == null || !sttProc.isAlive()) {
            startSttProcess(); // try to restart if dead
            if (sttProc == null) return "__NO_LIB__";
        }
        try {
            sttStdin.write("listen\n"); sttStdin.flush();
            String line = sttStdout.readLine();
            return (line != null) ? line.trim() : "__SILENT__";
        } catch (Exception e) { System.out.println("[STT] query error: " + e.getMessage()); return "__ERR__"; }
    }

    void processSTTResult(String result) {
        System.out.println("[STT] result: " + result);
        if (result == null || result.isEmpty() || "__SILENT__".equals(result)) { transcriptArea.append("🤫 (silence detected)\n\n"); return; }
        if ("__UNCLEAR__".equals(result))  { transcriptArea.append("🤷 Couldn't understand that, speak more clearly\n\n"); return; }
        if ("__NO_NET__".equals(result))   { transcriptArea.append("📵 No internet connection for speech recognition\n\n"); return; }
        if ("__NO_LIB__".equals(result))   {
            transcriptArea.append("❌ speech_recognition not installed!\n   Run: pip3 install SpeechRecognition pyaudio\n\n");
            sttStatusLbl.setText("Install: pip3 install SpeechRecognition pyaudio"); sttStatusLbl.setForeground(C_RED); return;
        }
        if (result.startsWith("__ERR__"))  { transcriptArea.append("❓ Something went wrong (check terminal for details)\n\n"); return; }
        String lower = result.toLowerCase();
        List<String> foundBadWords = new ArrayList<>();
        String displayResult = result;
        for (String bw : BAD_WORDS) {
            String pattern = "(?i)\\b" + java.util.regex.Pattern.quote(bw) + "\\b";
            if (lower.matches(".*\\b" + java.util.regex.Pattern.quote(bw) + "\\b.*")) {
                foundBadWords.add(bw); displayResult = displayResult.replaceAll(pattern, censorWord(bw));
            }
        }
        transcriptArea.append("💬 \"" + displayResult + "\"\n\n");
        transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
        alerts.addAlert(new Alert(Alert.AlertType.SYSTEM, "Heard: " + displayResult, 1, "speech"));
        if (!foundBadWords.isEmpty()) triggerSnitchMode(foundBadWords, displayResult);
    }

    void triggerSnitchMode(List<String> words, String censoredText) {
        cussCount += words.size(); cussCountLbl.setText("🚨 Cuss Count: " + cussCount); cussCountLbl.setForeground(C_RED);
        String wordList = String.join(", ", new ArrayList<String>() {{ for (String w : words) add(censorWord(w)); }});
        badWordAlertLbl.setText("Caught: " + wordList); badWordAlertLbl.setForeground(C_RED);
        funnyMsgLbl.setText(SNITCH_MSGS[snitchMsgIdx % SNITCH_MSGS.length]); snitchMsgIdx++; funnyMsgLbl.setForeground(C_ORANGE);
        badWordAlertPanel.setBackground(new Color(80,18,18));
        badWordAlertPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_RED, 2), BorderFactory.createEmptyBorder(12,14,12,14)));
        AudioStuff.speak("Language alert detected.");
        alerts.addAlert(new Alert(Alert.AlertType.DEAF_URGENT, "Bad word detected: " + wordList, 2, "language"));
        javax.swing.Timer reset = new javax.swing.Timer(5000, e -> {
            badWordAlertPanel.setBackground(new Color(35,18,18));
            badWordAlertPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(255,75,75,60),1), BorderFactory.createEmptyBorder(12,14,12,14)));
            badWordAlertLbl.setForeground(C_SUBTEXT);
        });
        reset.setRepeats(false); reset.start();
    }

    // censors a word: first + last letter, stars in the middle
    String censorWord(String word) {
        if (word == null || word.isEmpty()) return "*";
        if (word.length() == 1) return "*";
        if (word.length() == 2) return word.charAt(0) + "*";
        char[] stars = new char[word.length() - 2]; Arrays.fill(stars, '*');
        return word.charAt(0) + new String(stars) + word.charAt(word.length() - 1);
    }

    void startSimTimer() {
        simTimer = new javax.swing.Timer(85, e -> {
            if (audio == null || !audio.isRunning()) { double fakeLv = AudioStuff.simulateLevel(); refreshDisplay(fakeLv, fakeLv * 1.04); }
        });
        simTimer.start();
    }

    void doTestAlert() {
        alerts.addAlert(new Alert(Alert.AlertType.DEAF_AUDIO, "Test alert — system working!", 1, "test"));
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

    // ── HELPERS ───────────────────────────────────────────────────────

    void addHdr(JPanel p, String text) {
        JLabel l = new JLabel(text.toUpperCase()); l.setFont(new Font("Arial", Font.BOLD, 10)); l.setForeground(C_SUBTEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER), BorderFactory.createEmptyBorder(0,0,6,0)));
        l.setMaximumSize(new Dimension(99999, 24)); p.add(l);
    }

    JLabel mkLbl(String text) {
        JLabel l = new JLabel(text); l.setFont(new Font("Arial", Font.PLAIN, 11)); l.setForeground(C_SUBTEXT); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l;
    }

    JProgressBar makeBar(Color fg, int h) {
        JProgressBar b = new JProgressBar(0, 100); b.setValue(0); b.setStringPainted(true); b.setString("0%");
        b.setForeground(fg); b.setBackground(new Color(38,38,58)); b.setMaximumSize(new Dimension(99999, h));
        b.setAlignmentX(Component.LEFT_ALIGNMENT); b.setFont(new Font("Arial", Font.BOLD, 11)); return b;
    }

    JButton makeBtn(String text, Color bg, Color fg, ActionListener a) {
        JButton b = new JButton(text); b.setBackground(bg); b.setForeground(fg); b.setOpaque(true); // mac ignores bg/fg without this
        b.setFont(new Font("Arial", Font.BOLD, 12)); b.setFocusPainted(false); b.setBorderPainted(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR)); b.addActionListener(a); return b;
    }

    public void cleanup() {
        if (simTimer   != null) simTimer.stop();
        if (flashTimer != null) flashTimer.stop();
        if (sttProc    != null) sttProc.destroy();
        System.out.println("[DeafMode] cleaned up");
    }
}
