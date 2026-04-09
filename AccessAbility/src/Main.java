import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.ArrayList;
import java.io.*;

// simple callback so showLoginScreen can hand results back to main()
interface LoginCallback { void onLogin(String name, String mode); }

// Main.java — main window for AccessAbility
// TSA Software Development 2026
// extends JFrame directly bc separating main() was getting confusing
//
// data structures used (5 total for TSA rubric):
//   1. ArrayList  - stores alert history
//   2. Queue      - processes alerts in order (FIFO)
//   3. TreeMap    - tracks alert categories sorted alphabetically
//   4. Stack      - undo last alert (LIFO)
//   5. HashMap    - stores user profiles

public class Main extends JFrame {

    String loginName, loginMode;
    AlertManager       alerts;
    UserProfileManager profiles;
    AudioStuff         audio;
    DeafMode  deafPanel;
    BlindMode blindPanel;
    CardLayout cardLayout;
    JPanel     cardContainer;
    JLabel modeLbl, statsLbl;
    String currentMode = "DEAF";

    static final Color C_BG      = new Color(15, 15, 27);
    static final Color C_PANEL   = new Color(22, 22, 40);
    static final Color C_BLUE    = new Color(64, 156, 255);
    static final Color C_GREEN   = new Color(50, 210, 100);
    static final Color C_PURPLE  = new Color(160, 110, 255);
    static final Color C_TEXT    = new Color(220, 220, 240);
    static final Color C_SUBTEXT = new Color(115, 115, 140);

    public Main(String userName, String mode) {
        loginName = (userName == null || userName.isEmpty()) ? "Guest" : userName;
        loginMode = (mode     == null || mode.isEmpty())     ? "DEAF"  : mode;
        System.out.println("Assistatron 5000 starting for: " + loginName + " (TSA 2026)");
        alerts   = new AlertManager();
        profiles = new UserProfileManager();
        profiles.addProfile(loginName);
        profiles.switchUser(loginName);
        audio = new AudioStuff();
        if (audio.initMic()) { audio.startListening(); System.out.println("[Main] mic working!!"); }
        else { System.out.println("[Main] mic failed, panels will simulate audio"); audio = null; }
        setTitle("Assistatron 5000  —  Hello, " + loginName + "!");
        setSize(960, 680);
        setMinimumSize(new Dimension(750, 500));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(C_BG);
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
        buildWindow();
        new Timer(1000, e -> { if (statsLbl != null) statsLbl.setText(alerts.getSummaryString()); }).start();
        SwingUtilities.invokeLater(() -> runSetupCheck());
        System.out.println("[Main] app is ready!!");
    }

    void buildWindow() {
        setLayout(new BorderLayout(0, 0));
        add(buildHeader(), BorderLayout.NORTH);
        deafPanel  = new DeafMode(audio, alerts, profiles);
        blindPanel = new BlindMode(alerts, profiles);
        cardLayout    = new CardLayout();
        cardContainer = new JPanel(cardLayout);
        cardContainer.setBackground(C_BG);
        cardContainer.add(deafPanel,  "DEAF");
        cardContainer.add(blindPanel, "BLIND");
        add(cardContainer, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        switchMode(loginMode);
    }

    // ── HEADER ────────────────────────────────────────────────────────
    JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(10, 0));
        h.setBackground(C_PANEL);
        h.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, C_BLUE),
            BorderFactory.createEmptyBorder(10, 18, 10, 18)));

        JPanel leftSide = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftSide.setBackground(C_PANEL);
        JLabel logoLbl = new JLabel("♿  Assistatron 5000");
        logoLbl.setFont(new Font("Arial", Font.BOLD, 22)); logoLbl.setForeground(C_BLUE);
        JLabel subtitleLbl = new JLabel("    Assistive Technology Platform");
        subtitleLbl.setFont(new Font("Arial", Font.ITALIC, 11)); subtitleLbl.setForeground(C_SUBTEXT);
        leftSide.add(logoLbl); leftSide.add(subtitleLbl);
        h.add(leftSide, BorderLayout.WEST);

        // mode switch buttons
        JPanel midSide = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        midSide.setBackground(C_PANEL);
        JButton deafBtn  = makeHdrBtn("🔊  Deaf Mode",  C_BLUE,   145, e -> switchMode("DEAF"));
        JButton blindBtn = makeHdrBtn("👁  Blind Mode", C_PURPLE, 145, e -> switchMode("BLIND"));
        deafBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { deafBtn.setBackground(C_BLUE.brighter()); }
            public void mouseExited (MouseEvent e) { deafBtn.setBackground(C_BLUE); }
        });
        blindBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { blindBtn.setBackground(C_PURPLE.brighter()); }
            public void mouseExited (MouseEvent e) { blindBtn.setBackground(C_PURPLE); }
        });
        midSide.add(deafBtn); midSide.add(blindBtn);
        h.add(midSide, BorderLayout.CENTER);

        // right side: user + settings + about
        JPanel rightSide = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightSide.setBackground(C_PANEL);
        JLabel userLbl = new JLabel("👤  " + loginName);
        userLbl.setFont(new Font("Arial", Font.BOLD, 13)); userLbl.setForeground(C_TEXT);
        JButton settingsBtn = makeIconBtn("⚙", "Settings", e -> openSettingsDialog());
        JButton aboutBtn    = makeIconBtn("ℹ", "About",    e -> showAbout());
        rightSide.add(userLbl); rightSide.add(settingsBtn); rightSide.add(aboutBtn);
        h.add(rightSide, BorderLayout.EAST);
        return h;
    }

    JButton makeHdrBtn(String text, Color bg, int w, ActionListener a) {
        JButton b = new JButton(text);
        b.setFont(new Font("Arial", Font.BOLD, 13)); b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setOpaque(true); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(w, 32)); b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(a); return b;
    }

    JButton makeIconBtn(String text, String tip, ActionListener a) {
        JButton b = new JButton(text);
        b.setFont(new Font("Arial", Font.PLAIN, 16)); b.setBackground(C_PANEL); b.setForeground(C_TEXT);
        b.setBorderPainted(false); b.setFocusPainted(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR)); b.setToolTipText(tip); b.addActionListener(a); return b;
    }

    // ── STATUS BAR ────────────────────────────────────────────────────
    JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(C_PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(48, 48, 68)),
            BorderFactory.createEmptyBorder(4, 14, 4, 14)));
        modeLbl  = new JLabel("Mode: Deaf Assistance");
        modeLbl.setFont(new Font("Arial", Font.BOLD, 11)); modeLbl.setForeground(C_BLUE);
        statsLbl = new JLabel("Welcome, " + loginName + "!  No alerts yet.");
        statsLbl.setFont(new Font("Arial", Font.PLAIN, 11)); statsLbl.setForeground(C_SUBTEXT);
        JLabel versionLbl = new JLabel("Assistatron 5000  |  TSA Software Development 2026");
        versionLbl.setFont(new Font("Arial", Font.PLAIN, 10)); versionLbl.setForeground(new Color(68, 68, 92));
        bar.add(modeLbl, BorderLayout.WEST); bar.add(statsLbl, BorderLayout.CENTER); bar.add(versionLbl, BorderLayout.EAST);
        return bar;
    }

    void switchMode(String mode) {
        currentMode = mode;
        cardLayout.show(cardContainer, mode);
        if ("DEAF".equals(mode)) { modeLbl.setText("Mode: Deaf Assistance"); modeLbl.setForeground(C_BLUE); }
        else                     { modeLbl.setText("Mode: Blind Assistance"); modeLbl.setForeground(C_PURPLE); }
        System.out.println("[Main] switched to " + mode + " mode");
    }

    // ── SETTINGS DIALOG ───────────────────────────────────────────────
    void openSettingsDialog() {
        JDialog dlg = new JDialog(this, "Settings", true);
        dlg.setSize(430, 460); dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(new Color(20, 20, 38));
        JPanel cp = new JPanel();
        cp.setBackground(new Color(20, 20, 38));
        cp.setLayout(new BoxLayout(cp, BoxLayout.Y_AXIS));
        cp.setBorder(BorderFactory.createEmptyBorder(20, 20, 10, 20));

        JLabel titleLbl = new JLabel("Settings");
        titleLbl.setFont(new Font("Arial", Font.BOLD, 20)); titleLbl.setForeground(C_BLUE);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        cp.add(titleLbl); cp.add(Box.createVerticalStrut(18));

        // TTS toggle
        JCheckBox ttsCheck = new JCheckBox("Enable Text-to-Speech");
        ttsCheck.setSelected(AudioStuff.ttsOn); ttsCheck.setBackground(new Color(20, 20, 38));
        ttsCheck.setForeground(C_TEXT); ttsCheck.setFont(new Font("Arial", Font.PLAIN, 13));
        ttsCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        ttsCheck.addActionListener(e -> AudioStuff.ttsOn = ttsCheck.isSelected());
        cp.add(ttsCheck); cp.add(Box.createVerticalStrut(10));

        // voice selector row
        JPanel voiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        voiceRow.setBackground(new Color(20, 20, 38)); voiceRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel voiceLbl = new JLabel("TTS Voice:"); voiceLbl.setForeground(C_TEXT); voiceLbl.setFont(new Font("Arial", Font.BOLD, 13));
        String[] voiceOpts = {"Alex", "Samantha", "Victoria", "Karen", "Daniel"};
        JComboBox<String> voiceBox = new JComboBox<>(voiceOpts);
        voiceBox.setSelectedItem(AudioStuff.currentVoice); voiceBox.setBackground(new Color(35, 35, 55)); voiceBox.setForeground(C_TEXT);
        voiceBox.addActionListener(e -> AudioStuff.currentVoice = (String) voiceBox.getSelectedItem());
        JButton testVoiceBtn = new JButton("Test");
        testVoiceBtn.setBackground(C_BLUE); testVoiceBtn.setForeground(Color.WHITE); testVoiceBtn.setOpaque(true);
        testVoiceBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testVoiceBtn.addActionListener(e -> AudioStuff.speak("Hello. Assistatron 5000 is working."));
        voiceRow.add(voiceLbl); voiceRow.add(voiceBox); voiceRow.add(testVoiceBtn);
        cp.add(voiceRow); cp.add(Box.createVerticalStrut(18));

        // add user profile
        JLabel addUserTitle = new JLabel("Add User Profile:");
        addUserTitle.setFont(new Font("Arial", Font.BOLD, 13)); addUserTitle.setForeground(C_TEXT); addUserTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        cp.add(addUserTitle); cp.add(Box.createVerticalStrut(5));
        JPanel userRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        userRow.setBackground(new Color(20, 20, 38)); userRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel nameLbl = new JLabel("Name:"); nameLbl.setForeground(C_TEXT); nameLbl.setFont(new Font("Arial", Font.PLAIN, 13));
        JTextField nameField = new JTextField(12);
        nameField.setBackground(new Color(35, 35, 55)); nameField.setForeground(C_TEXT); nameField.setCaretColor(C_TEXT);
        JButton addBtn = new JButton("Add");
        addBtn.setBackground(C_GREEN); addBtn.setForeground(Color.BLACK); addBtn.setOpaque(true);
        addBtn.setFont(new Font("Arial", Font.BOLD, 11));
        addBtn.addActionListener(e -> {
            String nameKorn = nameField.getText().trim();
            if (!nameKorn.isEmpty()) {
                if (profiles.addProfile(nameKorn)) { nameField.setText(""); JOptionPane.showMessageDialog(dlg, "Profile '" + nameKorn + "' created!!"); }
                else JOptionPane.showMessageDialog(dlg, "Name already taken or invalid");
            }
        });
        userRow.add(nameLbl); userRow.add(nameField); userRow.add(addBtn);
        cp.add(userRow); cp.add(Box.createVerticalStrut(22));

        // data structures list (TSA judges need to see this!!)
        JLabel dsTitle = new JLabel("Data Structures Used in This App:");
        dsTitle.setFont(new Font("Arial", Font.BOLD, 13)); dsTitle.setForeground(C_TEXT); dsTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        cp.add(dsTitle); cp.add(Box.createVerticalStrut(5));
        String[] dsLines = {
            "1. ArrayList<Alert>          - alert history storage",
            "2. Queue<Alert> (LinkedList)  - alert processing, FIFO order",
            "3. TreeMap<String,Integer>    - category frequency, auto-sorted",
            "4. Stack<String>             - undo history, LIFO order",
            "5. HashMap<String,Profile>   - user profiles, O(1) lookup"
        };
        for (String line : dsLines) {
            JLabel dsLbl = new JLabel(line);
            dsLbl.setFont(new Font("Monospaced", Font.PLAIN, 11)); dsLbl.setForeground(new Color(88, 198, 115));
            dsLbl.setAlignmentX(Component.LEFT_ALIGNMENT); cp.add(dsLbl);
        }
        cp.add(Box.createVerticalStrut(18));

        // live TreeMap data
        JLabel freqTitle = new JLabel("Live Alert Category Data (TreeMap, sorted A-Z):");
        freqTitle.setFont(new Font("Arial", Font.BOLD, 11)); freqTitle.setForeground(C_SUBTEXT); freqTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        cp.add(freqTitle); cp.add(Box.createVerticalStrut(3));
        Map<String, Integer> freqData = alerts.getCategoryFrequency();
        if (freqData.isEmpty()) {
            JLabel emptyLbl = new JLabel("  (use the app first to generate data)");
            emptyLbl.setFont(new Font("Monospaced", Font.PLAIN, 11)); emptyLbl.setForeground(C_SUBTEXT); emptyLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            cp.add(emptyLbl);
        } else {
            for (Map.Entry<String, Integer> entry : freqData.entrySet()) {
                JLabel entLbl = new JLabel("  " + entry.getKey() + ":  " + entry.getValue() + "x");
                entLbl.setFont(new Font("Monospaced", Font.PLAIN, 11)); entLbl.setForeground(C_BLUE); entLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
                cp.add(entLbl);
            }
        }

        JScrollPane scroll = new JScrollPane(cp);
        scroll.setBorder(null); scroll.getViewport().setBackground(new Color(20, 20, 38));
        JPanel btnPanel = new JPanel(); btnPanel.setBackground(new Color(20, 20, 38));
        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(C_BLUE); closeBtn.setForeground(Color.WHITE); closeBtn.setOpaque(true);
        closeBtn.setFont(new Font("Arial", Font.BOLD, 12)); closeBtn.addActionListener(e -> dlg.dispose());
        btnPanel.add(closeBtn);
        dlg.setLayout(new BorderLayout());
        dlg.add(scroll, BorderLayout.CENTER); dlg.add(btnPanel, BorderLayout.SOUTH);
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
            "About Assistatron 5000", JOptionPane.INFORMATION_MESSAGE);
    }

    // checks if required external tools are installed, warns if not
    void runSetupCheck() {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWin = os.contains("win");
        ArrayList<String[]> missing = new ArrayList<>();
        if (isWin && findFfmpegWin() == null) {
            missing.add(new String[]{"ffmpeg  (camera capture)",
                "brew install ffmpeg",
                "1. go to ffmpeg.org/download.html\n2. download windows build\n3. extract + add to PATH\n   (or: winget install ffmpeg)",
                "lets blind mode take real photos with your webcam"});
        }
        boolean hasPython = checkPython();
        if (!hasPython) {
            missing.add(new String[]{"Python 3",
                "brew install python3\n(or download from python.org)",
                "download from python.org/downloads\nmake sure to check 'Add to PATH' during install!!",
                "needed to run speech recognition"});
        }
        if (hasPython && !checkSpeechRecognition()) {
            missing.add(new String[]{"SpeechRecognition + pyaudio  (Python libraries)",
                "pip3 install SpeechRecognition pyaudio",
                "pip install SpeechRecognition pyaudio\n(if that fails try: pip install pyaudio --find-links https://pypi.org/project/pipwin/)",
                "does the actual speech-to-text in deaf mode"});
        }
        if (missing.isEmpty()) { System.out.println("[Setup] all tools found, were good to go!!"); return; }
        System.out.println("[Setup] " + missing.size() + " tool(s) missing, showing setup dialog");
        showMissingToolsDialog(missing);
    }

    static String findFfmpegWin() {
        String[] guesses = {"ffmpeg", "C:\\ffmpeg\\bin\\ffmpeg.exe", "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"};
        for (String g : guesses) {
            try { Process p = Runtime.getRuntime().exec(new String[]{g, "-version"}); p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); if (p.exitValue() == 0) return g; }
            catch (Exception e) {}
        }
        return null;
    }

    static boolean checkPython() {
        for (String cmd : new String[]{"python3", "python"}) {
            try { Process p = Runtime.getRuntime().exec(new String[]{cmd, "--version"}); p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); if (p.exitValue() == 0) return true; }
            catch (Exception e) {}
        }
        return false;
    }

    static boolean checkSpeechRecognition() {
        for (String cmd : new String[]{"python3", "python"}) {
            try { Process p = Runtime.getRuntime().exec(new String[]{cmd, "-c", "import speech_recognition"}); p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); if (p.exitValue() == 0) return true; }
            catch (Exception e) {}
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
        Color BG = new Color(13, 13, 23), CARD = new Color(22, 22, 38), BORDER = new Color(45, 45, 68);
        Color BLUE = new Color(64, 156, 255), PURPLE = new Color(160, 110, 255);
        Color TEXT = new Color(220, 220, 240), SUBTEXT = new Color(115, 115, 140);

        JFrame lf = new JFrame("Assistatron 5000");
        lf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        lf.setSize(460, 540); lf.setMinimumSize(new Dimension(400, 480));
        lf.setLocationRelativeTo(null); lf.getContentPane().setBackground(BG);
        lf.setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setBackground(BG); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(44, 50, 36, 50));

        JLabel logo = new JLabel("♿  Assistatron 5000");
        logo.setFont(new Font("Arial", Font.BOLD, 26)); logo.setForeground(BLUE); logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel tagline = new JLabel("Assistive Technology Platform");
        tagline.setFont(new Font("Arial", Font.ITALIC, 12)); tagline.setForeground(SUBTEXT); tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(logo); content.add(tagline); content.add(Box.createVerticalStrut(38));

        JLabel namePrompt = new JLabel("What's your name?");
        namePrompt.setFont(new Font("Arial", Font.BOLD, 15)); namePrompt.setForeground(TEXT); namePrompt.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(namePrompt); content.add(Box.createVerticalStrut(10));
        JTextField nameField = new JTextField();
        nameField.setMaximumSize(new Dimension(320, 40)); nameField.setBackground(CARD); nameField.setForeground(TEXT);
        nameField.setCaretColor(TEXT); nameField.setFont(new Font("Arial", Font.PLAIN, 15));
        nameField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BLUE, 1), BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        nameField.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(nameField); content.add(Box.createVerticalStrut(30));

        JLabel modePrompt = new JLabel("How can we help you today?");
        modePrompt.setFont(new Font("Arial", Font.BOLD, 15)); modePrompt.setForeground(TEXT); modePrompt.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(modePrompt); content.add(Box.createVerticalStrut(14));

        final String[] selectedMode = {"DEAF"};

        // build mode cards — hearingCard starts selected, visualCard starts unselected
        JPanel hearingCard = makeLoginCard(CARD); JPanel visualCard  = makeLoginCard(CARD);
        hearingCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BLUE,   2), BorderFactory.createEmptyBorder(14, 10, 14, 10)));
        visualCard .setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER, 2), BorderFactory.createEmptyBorder(14, 10, 14, 10)));
        addCardChildren(hearingCard, "🔊", "Hearing",  "Impairment", BLUE,   SUBTEXT);
        addCardChildren(visualCard,  "👁",  "Visual",   "Impairment", PURPLE, SUBTEXT);
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
        modeRow.setBackground(BG); modeRow.setMaximumSize(new Dimension(320, 115)); modeRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        modeRow.add(hearingCard); modeRow.add(visualCard);
        content.add(modeRow); content.add(Box.createVerticalStrut(24));

        JLabel errorLbl = new JLabel(" ");
        errorLbl.setFont(new Font("Arial", Font.ITALIC, 12)); errorLbl.setForeground(new Color(255, 85, 85)); errorLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(errorLbl); content.add(Box.createVerticalStrut(6));

        JButton goBtn = new JButton("Let's Go  →");
        goBtn.setFont(new Font("Arial", Font.BOLD, 15)); goBtn.setBackground(BLUE); goBtn.setForeground(Color.WHITE);
        goBtn.setOpaque(true); goBtn.setFocusPainted(false); goBtn.setBorderPainted(false);
        goBtn.setCursor(new Cursor(Cursor.HAND_CURSOR)); goBtn.setMaximumSize(new Dimension(200, 44)); goBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        goBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { goBtn.setBackground(BLUE.brighter()); }
            public void mouseExited (MouseEvent e) { goBtn.setBackground(BLUE); }
        });
        ActionListener submit = e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { errorLbl.setText("Please enter your name first!"); return; }
            lf.dispose(); cb.onLogin(name, selectedMode[0]);
        };
        goBtn.addActionListener(submit); nameField.addActionListener(submit);
        content.add(goBtn);
        lf.add(content, BorderLayout.CENTER); lf.setVisible(true); nameField.requestFocusInWindow();
    }

    // helper: make a blank login card panel
    static JPanel makeLoginCard(Color bg) {
        JPanel c = new JPanel(); c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBackground(bg); c.setCursor(new Cursor(Cursor.HAND_CURSOR)); return c;
    }

    // helper: add icon + title + sub to a login card
    static void addCardChildren(JPanel card, String icon, String title, String sub, Color titleColor, Color subColor) {
        JLabel ic = new JLabel(icon); ic.setFont(new Font("Dialog", Font.PLAIN, 30)); ic.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel tl = new JLabel(title); tl.setFont(new Font("Arial", Font.BOLD, 14)); tl.setForeground(titleColor); tl.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sl = new JLabel(sub);  sl.setFont(new Font("Arial", Font.PLAIN, 11)); sl.setForeground(subColor);   sl.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(ic); card.add(Box.createVerticalStrut(5)); card.add(tl); card.add(sl);
    }

    // ── MAIN METHOD ───────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception e) { System.out.println("look and feel failed: " + e.getMessage()); }
            showLoginScreen((name, mode) -> {
                Main app = new Main(name, mode);
                app.setVisible(true);
                String greeting = "DEAF".equals(mode)
                    ? "Welcome " + name + ". Deaf mode is active."
                    : "Welcome " + name + ". Blind mode is active. Press Start Scanning to begin.";
                AudioStuff.speak(greeting);
            });
        });
    }
}
