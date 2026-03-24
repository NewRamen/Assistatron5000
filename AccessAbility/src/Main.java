import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.ArrayList;
import java.io.*;

// Main.java
// THIS IS THE MAIN CLASS
// it extends JFrame so it is literally the window
// i know ur supposed to keep main() separate from the window class
// but this is easier and it works fine so
//
// TSA Software Development Event 2026
// Project: AccessAbility
// Topic: Assistive Technology for Deaf and Blind Users
//
// Problem: Deaf and Blind people still struggle with daily tasks even
// with all the technology we have. Most apps assume the user has no
// disability which is kinda a big problem
//
// Product: A dual mode app that:
//   Deaf Mode  → monitors audio levels, flashes visual alerts, uses TTS
//   Blind Mode → detects nearby objects (simulated), speaks what it sees
//
// CS3 DATA STRUCTURES (at least 3 required, we have 5):
//   1. ArrayList<Alert>          → alert history  (AlertManager.java)
//   2. Queue<Alert>              → FIFO processing (AlertManager.java)
//   3. TreeMap<String,Integer>   → category frequency, sorted (AlertManager.java)
//   4. Stack<String>             → undo history (AlertManager.java)
//   5. HashMap<String,UserProfile> → user profiles (AlertManager.java)
//
// to compile: javac -d out src/*.java
// to run:     java -cp out Main
//
// hardware needed: java 8+, microphone optional, mac/windows/linux

public class Main extends JFrame {

    // ── shared stuff (all panels use these) ──────────────────────────
    AlertManager       alerts;
    UserProfileManager profiles;
    AudioStuff         audio;

    // ── the two main panels ──────────────────────────────────────────
    DeafMode  deafPanel;
    BlindMode blindPanel;

    // ── layout stuff ─────────────────────────────────────────────────
    CardLayout cardLayout;
    JPanel     cardContainer; // holds both panels, switches between them

    // ── status bar labels ─────────────────────────────────────────────
    JLabel modeLbl;
    JLabel statsLbl;

    String currentMode = "DEAF"; // track which mode is showing

    // ── colors ───────────────────────────────────────────────────────
    // copied same colors from the panel files so everything looks consistent
    static final Color C_BG      = new Color(15, 15, 27);
    static final Color C_PANEL   = new Color(22, 22, 40);
    static final Color C_BLUE    = new Color(64, 156, 255);
    static final Color C_GREEN   = new Color(50, 210, 100);
    static final Color C_PURPLE  = new Color(160, 110, 255);
    static final Color C_TEXT    = new Color(220, 220, 240);
    static final Color C_SUBTEXT = new Color(115, 115, 140);

    // constructor - sets up EVERYTHING
    public Main() {
        System.out.println("Assistatron 5000 starting...");
        System.out.println("TSA Software Development 2026");

        // step 1: make the data managers
        alerts   = new AlertManager();
        profiles = new UserProfileManager();

        // step 2: try to start the microphone
        audio = new AudioStuff();
        boolean micGotOpened = audio.initMic();
        if (micGotOpened == true) {
            audio.startListening();
            System.out.println("[Main] mic working!!");
        } else {
            System.out.println("[Main] mic failed, panels will simulate audio");
            audio = null; // set null so panels fall back to simulation
        }

        // step 3: setup the window itself
        setTitle("Assistatron 5000 v2.0  |  TSA Software Development 2026");
        setSize(960, 680);
        setMinimumSize(new Dimension(750, 500));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // center on screen
        getContentPane().setBackground(C_BG);

        // step 4: save and cleanup when window closes
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                System.out.println("[Main] shutting down...");
                if (audio      != null) audio.cleanup();
                if (deafPanel  != null) deafPanel.cleanup();
                if (blindPanel != null) blindPanel.cleanup();
                profiles.saveProfiles();
                System.out.println("[Main] bye!!! hope we win state lol");
            }
        });

        // step 5: build the actual window UI
        buildWindow();

        // step 6: stats update timer (updates bottom bar every second)
        new Timer(1000, e -> {
            if (statsLbl != null) {
                statsLbl.setText(alerts.getSummaryString());
            }
        }).start();

        // step 7: check that external tools r installed, warn if not
        // runs after window shows so the dialog has a parent
        SwingUtilities.invokeLater(() -> runSetupCheck());

        System.out.println("[Main] app is ready!!");
    }

    // builds the whole window layout
    void buildWindow() {
        setLayout(new BorderLayout(0, 0));

        // top: header with logo + mode buttons + user info
        add(buildHeader(), BorderLayout.NORTH);

        // center: the actual panels (deaf mode or blind mode)
        // CardLayout lets us switch between them by name
        deafPanel  = new DeafMode(audio, alerts, profiles);
        blindPanel = new BlindMode(alerts, profiles);

        cardLayout    = new CardLayout();
        cardContainer = new JPanel(cardLayout);
        cardContainer.setBackground(C_BG);
        cardContainer.add(deafPanel,  "DEAF");
        cardContainer.add(blindPanel, "BLIND");
        add(cardContainer, BorderLayout.CENTER);

        // bottom: status bar
        add(buildStatusBar(), BorderLayout.SOUTH);

        // start on deaf mode
        switchMode("DEAF");
    }

    // ── HEADER ────────────────────────────────────────────────────────
    JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(10, 0));
        h.setBackground(C_PANEL);
        h.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, C_BLUE),
            BorderFactory.createEmptyBorder(10, 18, 10, 18)
        ));

        // left side: logo + subtitle
        JPanel leftSide = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftSide.setBackground(C_PANEL);

        JLabel logoLbl = new JLabel("♿  Assistatron 5000");
        logoLbl.setFont(new Font("Arial", Font.BOLD, 22));
        logoLbl.setForeground(C_BLUE);

        JLabel subtitleLbl = new JLabel("    Assistive Technology Platform");
        subtitleLbl.setFont(new Font("Arial", Font.ITALIC, 11));
        subtitleLbl.setForeground(C_SUBTEXT);

        leftSide.add(logoLbl);
        leftSide.add(subtitleLbl);
        h.add(leftSide, BorderLayout.WEST);

        // middle: mode switch buttons
        JPanel midSide = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        midSide.setBackground(C_PANEL);

        // deaf mode button
        JButton deafBtn = new JButton("🔊  Deaf Mode");
        deafBtn.setFont(new Font("Arial", Font.BOLD, 13));
        deafBtn.setBackground(C_BLUE);
        deafBtn.setForeground(Color.WHITE);
        deafBtn.setBorderPainted(false);
        deafBtn.setFocusPainted(false);
        deafBtn.setPreferredSize(new Dimension(145, 32));
        deafBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        deafBtn.addActionListener(e -> switchMode("DEAF"));
        // hover effect - i spent like an hour making this look good
        deafBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { deafBtn.setBackground(C_BLUE.brighter());  }
            public void mouseExited(MouseEvent e)  { deafBtn.setBackground(C_BLUE);             }
        });

        // blind mode button (basically copy paste from above)
        JButton blindBtn = new JButton("👁  Blind Mode");
        blindBtn.setFont(new Font("Arial", Font.BOLD, 13));
        blindBtn.setBackground(C_PURPLE);
        blindBtn.setForeground(Color.WHITE);
        blindBtn.setBorderPainted(false);
        blindBtn.setFocusPainted(false);
        blindBtn.setPreferredSize(new Dimension(145, 32));
        blindBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        blindBtn.addActionListener(e -> switchMode("BLIND"));
        blindBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { blindBtn.setBackground(C_PURPLE.brighter()); }
            public void mouseExited(MouseEvent e)  { blindBtn.setBackground(C_PURPLE);            }
        });

        midSide.add(deafBtn);
        midSide.add(blindBtn);
        h.add(midSide, BorderLayout.CENTER);

        // right side: user name + settings button + about button
        JPanel rightSide = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightSide.setBackground(C_PANEL);

        JLabel userLbl = new JLabel("👤  " + profiles.getCurrentName());
        userLbl.setFont(new Font("Arial", Font.PLAIN, 13));
        userLbl.setForeground(C_TEXT);

        JButton settingsBtn = new JButton("⚙");
        settingsBtn.setFont(new Font("Arial", Font.PLAIN, 16));
        settingsBtn.setBackground(C_PANEL);
        settingsBtn.setForeground(C_TEXT);
        settingsBtn.setBorderPainted(false);
        settingsBtn.setFocusPainted(false);
        settingsBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        settingsBtn.setToolTipText("Settings");
        settingsBtn.addActionListener(e -> openSettingsDialog());

        JButton aboutBtn = new JButton("ℹ");
        aboutBtn.setFont(new Font("Arial", Font.PLAIN, 16));
        aboutBtn.setBackground(C_PANEL);
        aboutBtn.setForeground(C_TEXT);
        aboutBtn.setBorderPainted(false);
        aboutBtn.setFocusPainted(false);
        aboutBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        aboutBtn.setToolTipText("About");
        aboutBtn.addActionListener(e -> showAbout());

        rightSide.add(userLbl);
        rightSide.add(settingsBtn);
        rightSide.add(aboutBtn);
        h.add(rightSide, BorderLayout.EAST);

        return h;
    }

    // ── STATUS BAR ────────────────────────────────────────────────────
    JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(C_PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(48, 48, 68)),
            BorderFactory.createEmptyBorder(4, 14, 4, 14)
        ));

        modeLbl = new JLabel("Mode: Deaf Assistance");
        modeLbl.setFont(new Font("Arial", Font.BOLD, 11));
        modeLbl.setForeground(C_BLUE);

        statsLbl = new JLabel("no alerts yet");
        statsLbl.setFont(new Font("Arial", Font.PLAIN, 11));
        statsLbl.setForeground(C_SUBTEXT);

        JLabel versionLbl = new JLabel("Assistatron 5000  |  TSA Software Development 2026");
        versionLbl.setFont(new Font("Arial", Font.PLAIN, 10));
        versionLbl.setForeground(new Color(68, 68, 92));

        bar.add(modeLbl,    BorderLayout.WEST);
        bar.add(statsLbl,   BorderLayout.CENTER);
        bar.add(versionLbl, BorderLayout.EAST);

        return bar;
    }

    // switch between deaf mode and blind mode
    void switchMode(String mode) {
        currentMode = mode;
        cardLayout.show(cardContainer, mode);
        if ("DEAF".equals(mode)) {
            modeLbl.setText("Mode: Deaf Assistance");
            modeLbl.setForeground(C_BLUE);
        } else {
            modeLbl.setText("Mode: Blind Assistance");
            modeLbl.setForeground(C_PURPLE);
        }
        System.out.println("[Main] switched to " + mode + " mode");
    }

    // ── SETTINGS DIALOG ───────────────────────────────────────────────
    // honestly this dialog took forever to build but it looks good
    void openSettingsDialog() {
        JDialog dlg = new JDialog(this, "Settings", true);
        dlg.setSize(430, 460);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(new Color(20, 20, 38));

        JPanel contentPanel = new JPanel();
        contentPanel.setBackground(new Color(20, 20, 38));
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 10, 20));

        // title
        JLabel titleLbl = new JLabel("Settings");
        titleLbl.setFont(new Font("Arial", Font.BOLD, 20));
        titleLbl.setForeground(C_BLUE);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(titleLbl);
        contentPanel.add(Box.createVerticalStrut(18));

        // TTS toggle checkbox
        JCheckBox ttsCheck = new JCheckBox("Enable Text-to-Speech");
        ttsCheck.setSelected(AudioStuff.ttsOn);
        ttsCheck.setBackground(new Color(20, 20, 38));
        ttsCheck.setForeground(C_TEXT);
        ttsCheck.setFont(new Font("Arial", Font.PLAIN, 13));
        ttsCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        ttsCheck.addActionListener(e -> AudioStuff.ttsOn = ttsCheck.isSelected());
        contentPanel.add(ttsCheck);
        contentPanel.add(Box.createVerticalStrut(10));

        // voice selector row
        JPanel voiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        voiceRow.setBackground(new Color(20, 20, 38));
        voiceRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel voiceLbl = new JLabel("TTS Voice:");
        voiceLbl.setForeground(C_TEXT);
        voiceLbl.setFont(new Font("Arial", Font.BOLD, 13));

        String[] voiceOpts = {"Alex", "Samantha", "Victoria", "Karen", "Daniel"};
        JComboBox<String> voiceBox = new JComboBox<>(voiceOpts);
        voiceBox.setSelectedItem(AudioStuff.currentVoice);
        voiceBox.setBackground(new Color(35, 35, 55));
        voiceBox.setForeground(C_TEXT);
        voiceBox.addActionListener(e -> AudioStuff.currentVoice = (String) voiceBox.getSelectedItem());

        JButton testVoiceBtn = new JButton("Test");
        testVoiceBtn.setBackground(C_BLUE);
        testVoiceBtn.setForeground(Color.WHITE);
        testVoiceBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testVoiceBtn.addActionListener(e -> AudioStuff.speak("Hello. Assistatron 5000 is working."));

        voiceRow.add(voiceLbl);
        voiceRow.add(voiceBox);
        voiceRow.add(testVoiceBtn);
        contentPanel.add(voiceRow);
        contentPanel.add(Box.createVerticalStrut(18));

        // add new user profile
        JLabel addUserTitle = new JLabel("Add User Profile:");
        addUserTitle.setFont(new Font("Arial", Font.BOLD, 13));
        addUserTitle.setForeground(C_TEXT);
        addUserTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(addUserTitle);
        contentPanel.add(Box.createVerticalStrut(5));

        JPanel userRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        userRow.setBackground(new Color(20, 20, 38));
        userRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel nameLbl = new JLabel("Name:");
        nameLbl.setForeground(C_TEXT);
        nameLbl.setFont(new Font("Arial", Font.PLAIN, 13));

        JTextField nameField = new JTextField(12);
        nameField.setBackground(new Color(35, 35, 55));
        nameField.setForeground(C_TEXT);
        nameField.setCaretColor(C_TEXT);

        JButton addBtn = new JButton("Add");
        addBtn.setBackground(C_GREEN);
        addBtn.setForeground(Color.BLACK);
        addBtn.setFont(new Font("Arial", Font.BOLD, 11));
        addBtn.addActionListener(e -> {
            String nameKorn = nameField.getText().trim();
            if (!nameKorn.isEmpty()) {
                if (profiles.addProfile(nameKorn)) {
                    nameField.setText("");
                    JOptionPane.showMessageDialog(dlg, "Profile '" + nameKorn + "' created!!");
                } else {
                    JOptionPane.showMessageDialog(dlg, "Name already taken or invalid");
                }
            }
        });

        userRow.add(nameLbl);
        userRow.add(nameField);
        userRow.add(addBtn);
        contentPanel.add(userRow);
        contentPanel.add(Box.createVerticalStrut(22));

        // data structures list (important for TSA judges to see!!)
        JLabel dsTitle = new JLabel("Data Structures Used in This App:");
        dsTitle.setFont(new Font("Arial", Font.BOLD, 13));
        dsTitle.setForeground(C_TEXT);
        dsTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(dsTitle);
        contentPanel.add(Box.createVerticalStrut(5));

        String[] dsLines = {
            "1. ArrayList<Alert>          - alert history storage",
            "2. Queue<Alert> (LinkedList)  - alert processing, FIFO order",
            "3. TreeMap<String,Integer>    - category frequency, auto-sorted",
            "4. Stack<String>             - undo history, LIFO order",
            "5. HashMap<String,Profile>   - user profiles, O(1) lookup"
        };
        for (String line : dsLines) {
            JLabel dsLbl = new JLabel(line);
            dsLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
            dsLbl.setForeground(new Color(88, 198, 115));
            dsLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(dsLbl);
        }
        contentPanel.add(Box.createVerticalStrut(18));

        // show live alert category data from TreeMap
        JLabel freqTitle = new JLabel("Live Alert Category Data (TreeMap, sorted A-Z):");
        freqTitle.setFont(new Font("Arial", Font.BOLD, 11));
        freqTitle.setForeground(C_SUBTEXT);
        freqTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(freqTitle);
        contentPanel.add(Box.createVerticalStrut(3));

        Map<String, Integer> freqData = alerts.getCategoryFrequency();
        if (freqData.isEmpty()) {
            JLabel emptyLbl = new JLabel("  (use the app first to generate data)");
            emptyLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
            emptyLbl.setForeground(C_SUBTEXT);
            emptyLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(emptyLbl);
        } else {
            // TreeMap keeps these in alphabetical order automatically
            for (Map.Entry<String, Integer> entry : freqData.entrySet()) {
                JLabel entLbl = new JLabel("  " + entry.getKey() + ":  " + entry.getValue() + "x");
                entLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
                entLbl.setForeground(C_BLUE);
                entLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(entLbl);
            }
        }

        // add scroll pane bc content might be too tall
        JScrollPane scroll = new JScrollPane(contentPanel);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(new Color(20, 20, 38));
        dlg.add(scroll, BorderLayout.CENTER);

        // close button at bottom
        JPanel btnPanel = new JPanel();
        btnPanel.setBackground(new Color(20, 20, 38));

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(C_BLUE);
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setFont(new Font("Arial", Font.BOLD, 12));
        closeBtn.addActionListener(e -> dlg.dispose());

        btnPanel.add(closeBtn);
        dlg.add(btnPanel, BorderLayout.SOUTH);

        dlg.setLayout(new BorderLayout());
        dlg.add(scroll,    BorderLayout.CENTER);
        dlg.add(btnPanel,  BorderLayout.SOUTH);
        dlg.setVisible(true);
    }

    // ── ABOUT POPUP ───────────────────────────────────────────────────
    void showAbout() {
        JOptionPane.showMessageDialog(this,
            "<html><div style='font-family:Arial; padding:8px'>"
            + "<h2 style='color:#4099FF'>♿ Assistatron 5000 </h2>"
            + "<b>TSA Software Development Event 2026</b><br>"
            + "<i>Theme: Technology Serving Humanity</i><br><br>"
            + "<b>Problem:</b> Deaf and Blind users struggle with daily tasks bc "
            + "most tech assumes u have no disabilities<br><br>"
            + "<b>Deaf Mode:</b>  real-time audio monitoring, flashing visual alerts, TTS<br>"
            + "<b>Blind Mode:</b> object detection simulation, proximity warnings, TTS<br><br>"
            + "<b>Built with:</b><br>"
            + "- Java SE  (all the Swing UI)<br>"
            + "- javax.sound.sampled  (microphone input)<br>"
            + "- macOS 'say' command  (text to speech)<br><br>"
            + "<b>5 Data Structures:</b> ArrayList, Queue, TreeMap, Stack, HashMap<br><br>"
            + "<i>Made for CS3 Final Project + TSA 2026<br>"
            + "this took so long to make omg</i>"
            + "</div></html>",
            "About Assistatron 5000",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    // checks if required external tools are installed
    // shows a dialog with install instructions if anything is missing
    // works for both mac and windows (finally)
    void runSetupCheck() {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isMac = os.contains("mac");
        boolean isWin = os.contains("win");

        ArrayList<String[]> missing = new ArrayList<>();
        // each entry: { toolName, macInstructions, windowsInstructions, whatItDoes }

        // check camera tool
        if (isMac) {
            if (BlindMode.findImagesnap() == null) {
                missing.add(new String[]{
                    "imagesnap  (camera capture)",
                    "brew install imagesnap\n(need Homebrew first: brew.sh)",
                    "N/A — imagesnap is mac only",
                    "lets blind mode take real photos with your webcam"
                });
            }
        } else if (isWin) {
            if (findFfmpegWin() == null) {
                missing.add(new String[]{
                    "ffmpeg  (camera capture)",
                    "brew install ffmpeg",
                    "1. go to ffmpeg.org/download.html\n2. download windows build\n3. extract + add to PATH\n   (or: winget install ffmpeg)",
                    "lets blind mode take real photos with your webcam"
                });
            }
        }

        // check python3
        boolean hasPython = checkPython();
        if (!hasPython) {
            missing.add(new String[]{
                "Python 3",
                "brew install python3\n(or download from python.org)",
                "download from python.org/downloads\nmake sure to check 'Add to PATH' during install!!",
                "needed to run speech recognition"
            });
        }

        // check speech_recognition library (only if python exists)
        if (hasPython && !checkSpeechRecognition()) {
            missing.add(new String[]{
                "SpeechRecognition + pyaudio  (Python libraries)",
                "pip3 install SpeechRecognition pyaudio",
                "pip install SpeechRecognition pyaudio\n(if that fails try: pip install pyaudio --find-links https://pypi.org/project/pipwin/)",
                "does the actual speech-to-text in deaf mode"
            });
        }

        if (missing.isEmpty()) {
            System.out.println("[Setup] all tools found, were good to go!!");
            return; // everything installed, dont show dialog
        }

        // build and show the missing tools dialog
        System.out.println("[Setup] " + missing.size() + " tool(s) missing, showing setup dialog");
        showMissingToolsDialog(missing);
    }

    static String findFfmpegWin() {
        // ffmpeg isnt always in PATH on windows so check common locations
        String[] guesses = {"ffmpeg", "C:\\ffmpeg\\bin\\ffmpeg.exe", "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"};
        for (String g : guesses) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{g, "-version"});
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                if (p.exitValue() == 0) return g;
            } catch (Exception e) { /* not here */ }
        }
        return null;
    }

    static boolean checkPython() {
        // try python3 first (mac/linux), then python (windows usually uses python not python3)
        String[] cmds = {"python3", "python"};
        for (String cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{cmd, "--version"});
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                if (p.exitValue() == 0) return true;
            } catch (Exception e) { /* nope */ }
        }
        return false;
    }

    static boolean checkSpeechRecognition() {
        // try to import speech_recognition in python, if it fails its not installed
        String[] pythonCmds = {"python3", "python"};
        for (String cmd : pythonCmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{cmd, "-c", "import speech_recognition"});
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (p.exitValue() == 0) return true;
            } catch (Exception e) { /* nope */ }
        }
        return false;
    }

    void showMissingToolsDialog(ArrayList<String[]> missing) {
        JDialog dlg = new JDialog(this, "Setup Required — Missing Tools", true);
        dlg.setSize(520, 420);
        dlg.setLocationRelativeTo(this);

        JPanel content = new JPanel();
        content.setBackground(new Color(14, 14, 26));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 10, 20));

        JLabel header = new JLabel("⚠  Some features need extra tools installed");
        header.setFont(new Font("Arial", Font.BOLD, 15));
        header.setForeground(new Color(255, 200, 50));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(header);

        JLabel sub = new JLabel("the app still runs but these features wont work without them:");
        sub.setFont(new Font("Arial", Font.ITALIC, 11));
        sub.setForeground(new Color(110, 110, 140));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(sub);
        content.add(Box.createVerticalStrut(16));

        String os = System.getProperty("os.name").toLowerCase();
        boolean isMac = os.contains("mac");

        for (String[] tool : missing) {
            // tool card
            JPanel card = new JPanel();
            card.setBackground(new Color(22, 22, 38));
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 90, 90, 80), 1),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
            ));
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.setMaximumSize(new Dimension(99999, 200));

            JLabel nameLbl = new JLabel("❌  " + tool[0]);
            nameLbl.setFont(new Font("Arial", Font.BOLD, 13));
            nameLbl.setForeground(new Color(255, 100, 100));
            nameLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(nameLbl);

            JLabel whatLbl = new JLabel("what it does: " + tool[3]);
            whatLbl.setFont(new Font("Arial", Font.ITALIC, 11));
            whatLbl.setForeground(new Color(110, 110, 140));
            whatLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(whatLbl);
            card.add(Box.createVerticalStrut(7));

            // mac instructions
            JLabel macTitle = new JLabel("🍎  Mac:");
            macTitle.setFont(new Font("Arial", Font.BOLD, 11));
            macTitle.setForeground(new Color(82, 168, 255));
            macTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(macTitle);
            for (String line : tool[1].split("\n")) {
                JLabel l = new JLabel("   " + line);
                l.setFont(new Font("Monospaced", Font.PLAIN, 11));
                l.setForeground(new Color(52, 211, 105));
                l.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(l);
            }
            card.add(Box.createVerticalStrut(5));

            // windows instructions
            JLabel winTitle = new JLabel("🪟  Windows:");
            winTitle.setFont(new Font("Arial", Font.BOLD, 11));
            winTitle.setForeground(new Color(82, 168, 255));
            winTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(winTitle);
            for (String line : tool[2].split("\n")) {
                JLabel l = new JLabel("   " + line);
                l.setFont(new Font("Monospaced", Font.PLAIN, 11));
                l.setForeground(new Color(52, 211, 105));
                l.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(l);
            }

            content.add(card);
            content.add(Box.createVerticalStrut(10));
        }

        JLabel restartNote = new JLabel("after installing, restart the app for changes to take effect");
        restartNote.setFont(new Font("Arial", Font.ITALIC, 10));
        restartNote.setForeground(new Color(90, 90, 120));
        restartNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(restartNote);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(new Color(14, 14, 26));

        JButton okBtn = new JButton("Got it, close");
        okBtn.setBackground(new Color(82, 168, 255));
        okBtn.setForeground(Color.WHITE);
        okBtn.setFont(new Font("Arial", Font.BOLD, 12));
        okBtn.setFocusPainted(false);
        okBtn.setBorderPainted(false);
        okBtn.addActionListener(e -> dlg.dispose());

        JPanel btnRow = new JPanel();
        btnRow.setBackground(new Color(14, 14, 26));
        btnRow.add(okBtn);

        dlg.setLayout(new BorderLayout());
        dlg.add(scroll,  BorderLayout.CENTER);
        dlg.add(btnRow,  BorderLayout.SOUTH);
        dlg.setVisible(true);
    }

    // ── MAIN METHOD ───────────────────────────────────────────────────
    public static void main(String[] args) {
        // swing needs to run on the Event Dispatch Thread (EDT)
        // if u dont do this it crashes in weird random ways
        // learned this the hard way (spent 2 hours debugging it)
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                // try to use the OS look and feel so it doesnt look so default java
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception e) {
                    // whatever if it fails it still works
                    System.out.println("look and feel failed: " + e.getMessage());
                }

                new Main().setVisible(true);
            }
        });
    }
}
