import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.ArrayList;
import java.io.*;

// simple callback so showLoginScreen can hand results back to main()
interface LoginCallback { void onLogin(String name, String mode); }

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
// with all the technology we have Most apps assume the user has no
// disability which is kinda a big problem
//
// Product: A dual mode app that:
//   Deaf Mode  → monitors audio levels, flashes visual alerts, uses TTS
//   Blind Mode → detects nearby objects 
//
// TSA:
//   1. ArrayList<Alert>          alert history  (AlertManager.java)
//   2. Queue<Alert>              FIFO processing (AlertManager.java)
//   3. TreeMap<String,Integer>   category frequency, sorted (AlertManager.java)
//   4. Stack<String>            undo history (AlertManager.java)
//   5. HashMap<String,UserProfile>user profiles (AlertManager.java)
//
//
// hardware needed: java 8+, microphone optional, mac/windows/linux

public class Main extends JFrame {

    // ── login info set before the window is built ────────────────────
    String loginName;   // entered on the login screen
    String loginMode;   // "DEAF" or "BLIND" — chosen on login screen

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
    public Main(String userName, String mode) {
        loginName = (userName == null || userName.isEmpty()) ? "Guest" : userName;
        loginMode = (mode     == null || mode.isEmpty())     ? "DEAF"  : mode;
        System.out.println("Assistatron 5000 starting for user: " + loginName);
        System.out.println("TSA Software Development 2026");

        // step 1: make the data managers
        alerts   = new AlertManager();
        profiles = new UserProfileManager();

        // register the logged-in user as a profile
        profiles.addProfile(loginName);
        profiles.switchUser(loginName);

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
        setTitle("Assistatron 5000  —  Hello, " + loginName + "!");
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

        // start on the mode the user chose at login
        switchMode(loginMode);
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

        JLabel userLbl = new JLabel("👤  " + loginName);
        userLbl.setFont(new Font("Arial", Font.BOLD, 13));
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

        statsLbl = new JLabel("Welcome, " + loginName + "!  No alerts yet.");
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

        // check camera tool (now uses opencv via python, so just check python+cv2)
        if (isWin) {
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
        StringBuilder msg = new StringBuilder("Some features need extra tools:\n\n");
        for (String[] tool : missing) {
            msg.append("• ").append(tool[0]).append("\n");
            msg.append("  Mac:     ").append(tool[1].replace("\n", "\n           ")).append("\n");
            msg.append("  Windows: ").append(tool[2].replace("\n", "\n           ")).append("\n\n");
        }
        msg.append("Restart the app after installing.");
        JOptionPane.showMessageDialog(this, msg.toString(), "Setup Required", JOptionPane.WARNING_MESSAGE);
    }

    // ── LOGIN SCREEN ──────────────────────────────────────────────────
    static void showLoginScreen(LoginCallback cb) {
        // colors (same palette as the rest of the app)
        Color BG      = new Color(13, 13, 23);
        Color CARD    = new Color(22, 22, 38);
        Color BORDER  = new Color(45, 45, 68);
        Color BLUE    = new Color(64, 156, 255);
        Color PURPLE  = new Color(160, 110, 255);
        Color TEXT    = new Color(220, 220, 240);
        Color SUBTEXT = new Color(115, 115, 140);

        JFrame lf = new JFrame("Assistatron 5000");
        lf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        lf.setSize(460, 540);
        lf.setMinimumSize(new Dimension(400, 480));
        lf.setLocationRelativeTo(null);
        lf.getContentPane().setBackground(BG);
        lf.setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setBackground(BG);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(44, 50, 36, 50));

        // ── logo ──
        JLabel logo = new JLabel("♿  Assistatron 5000");
        logo.setFont(new Font("Arial", Font.BOLD, 26));
        logo.setForeground(BLUE);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(logo);

        JLabel tagline = new JLabel("Assistive Technology Platform");
        tagline.setFont(new Font("Arial", Font.ITALIC, 12));
        tagline.setForeground(SUBTEXT);
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(tagline);
        content.add(Box.createVerticalStrut(38));

        // ── name field ──
        JLabel namePrompt = new JLabel("What's your name?");
        namePrompt.setFont(new Font("Arial", Font.BOLD, 15));
        namePrompt.setForeground(TEXT);
        namePrompt.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(namePrompt);
        content.add(Box.createVerticalStrut(10));

        JTextField nameField = new JTextField();
        nameField.setMaximumSize(new Dimension(320, 40));
        nameField.setPreferredSize(new Dimension(320, 40));
        nameField.setBackground(CARD);
        nameField.setForeground(TEXT);
        nameField.setCaretColor(TEXT);
        nameField.setFont(new Font("Arial", Font.PLAIN, 15));
        nameField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BLUE, 1),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        nameField.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(nameField);
        content.add(Box.createVerticalStrut(30));

        // ── mode selection ──
        JLabel modePrompt = new JLabel("How can we help you today?");
        modePrompt.setFont(new Font("Arial", Font.BOLD, 15));
        modePrompt.setForeground(TEXT);
        modePrompt.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(modePrompt);
        content.add(Box.createVerticalStrut(14));

        // track which card is selected — default to DEAF
        final String[] selectedMode = {"DEAF"};

        // helper: build one of the two big clickable mode cards
        // returns the card panel; border highlight is updated by the click handler below
        JPanel hearingCard = new JPanel();
        hearingCard.setLayout(new BoxLayout(hearingCard, BoxLayout.Y_AXIS));
        hearingCard.setBackground(CARD);
        hearingCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BLUE, 2),         // starts selected
            BorderFactory.createEmptyBorder(14, 10, 14, 10)));
        hearingCard.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JLabel hIcon = new JLabel("🔊"); hIcon.setFont(new Font("Dialog", Font.PLAIN, 30)); hIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hTitle = new JLabel("Hearing"); hTitle.setFont(new Font("Arial", Font.BOLD, 14)); hTitle.setForeground(BLUE); hTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hSub = new JLabel("Impairment"); hSub.setFont(new Font("Arial", Font.PLAIN, 11)); hSub.setForeground(SUBTEXT); hSub.setAlignmentX(Component.CENTER_ALIGNMENT);
        hearingCard.add(hIcon); hearingCard.add(Box.createVerticalStrut(5)); hearingCard.add(hTitle); hearingCard.add(hSub);

        JPanel visualCard = new JPanel();
        visualCard.setLayout(new BoxLayout(visualCard, BoxLayout.Y_AXIS));
        visualCard.setBackground(CARD);
        visualCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 2),       // starts unselected
            BorderFactory.createEmptyBorder(14, 10, 14, 10)));
        visualCard.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JLabel vIcon = new JLabel("👁"); vIcon.setFont(new Font("Dialog", Font.PLAIN, 30)); vIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel vTitle = new JLabel("Visual"); vTitle.setFont(new Font("Arial", Font.BOLD, 14)); vTitle.setForeground(PURPLE); vTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel vSub = new JLabel("Impairment"); vSub.setFont(new Font("Arial", Font.PLAIN, 11)); vSub.setForeground(SUBTEXT); vSub.setAlignmentX(Component.CENTER_ALIGNMENT);
        visualCard.add(vIcon); visualCard.add(Box.createVerticalStrut(5)); visualCard.add(vTitle); visualCard.add(vSub);

        // click handlers — highlight selected, dim the other
        hearingCard.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                selectedMode[0] = "DEAF";
                hearingCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BLUE,   2), BorderFactory.createEmptyBorder(14,10,14,10)));
                visualCard .setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER, 2), BorderFactory.createEmptyBorder(14,10,14,10)));
            }
            public void mouseEntered(MouseEvent e) { if (!"DEAF".equals(selectedMode[0])) hearingCard.setBackground(new Color(28,28,48)); }
            public void mouseExited (MouseEvent e) { if (!"DEAF".equals(selectedMode[0])) hearingCard.setBackground(CARD); }
        });
        visualCard.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                selectedMode[0] = "BLIND";
                visualCard .setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(PURPLE, 2), BorderFactory.createEmptyBorder(14,10,14,10)));
                hearingCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER, 2), BorderFactory.createEmptyBorder(14,10,14,10)));
            }
            public void mouseEntered(MouseEvent e) { if (!"BLIND".equals(selectedMode[0])) visualCard.setBackground(new Color(28,28,48)); }
            public void mouseExited (MouseEvent e) { if (!"BLIND".equals(selectedMode[0])) visualCard.setBackground(CARD); }
        });

        JPanel modeRow = new JPanel(new GridLayout(1, 2, 14, 0));
        modeRow.setBackground(BG);
        modeRow.setMaximumSize(new Dimension(320, 115));
        modeRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        modeRow.add(hearingCard);
        modeRow.add(visualCard);
        content.add(modeRow);
        content.add(Box.createVerticalStrut(24));

        // error label
        JLabel errorLbl = new JLabel(" ");
        errorLbl.setFont(new Font("Arial", Font.ITALIC, 12));
        errorLbl.setForeground(new Color(255, 85, 85));
        errorLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(errorLbl);
        content.add(Box.createVerticalStrut(6));

        // submit button
        JButton goBtn = new JButton("Let's Go  →");
        goBtn.setFont(new Font("Arial", Font.BOLD, 15));
        goBtn.setBackground(BLUE);
        goBtn.setForeground(Color.WHITE);
        goBtn.setFocusPainted(false);
        goBtn.setBorderPainted(false);
        goBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        goBtn.setMaximumSize(new Dimension(200, 44));
        goBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        goBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { goBtn.setBackground(BLUE.brighter()); }
            public void mouseExited (MouseEvent e) { goBtn.setBackground(BLUE); }
        });

        ActionListener submit = e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { errorLbl.setText("Please enter your name first!"); return; }
            lf.dispose();
            cb.onLogin(name, selectedMode[0]);
        };
        goBtn.addActionListener(submit);
        nameField.addActionListener(submit); // enter key submits too

        content.add(goBtn);

        lf.add(content, BorderLayout.CENTER);
        lf.setVisible(true);
        nameField.requestFocusInWindow();
    }

    // ── MAIN METHOD ───────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                System.out.println("look and feel failed: " + e.getMessage());
            }

            // show login screen first; when the user submits it creates the main window
            showLoginScreen((name, mode) -> {
                Main app = new Main(name, mode);
                app.setVisible(true);
                // personalized TTS greeting
                String greeting = "DEAF".equals(mode)
                    ? "Welcome " + name + ". Deaf mode is active."
                    : "Welcome " + name + ". Blind mode is active. Press Start Scanning to begin.";
                AudioStuff.speak(greeting);
            });
        });
    }
}
