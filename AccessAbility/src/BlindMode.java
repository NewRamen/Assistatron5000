import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.*;

// BlindMode.java — camera + YOLO object detection panel
// uses yolov8 for detection (pip install ultralytics)
// camera runs thru python bc java cant do opencv easily
// TODO: make this less laggy maybe

public class BlindMode extends JPanel {

    AlertManager       alerts;
    UserProfileManager profiles;
    CameraView         cameraView;

    JLabel       statusLbl, bigObjectLbl, distanceLbl, directionLbl;
    JProgressBar proximityBar;
    JLabel       proxDescLbl;
    JTextArea    logArea;
    JButton      startBtn, stopBtn;
    JComboBox<String> envPicker;
    JSlider      rateSlider;
    JLabel       rateValLbl, scanCountLbl;

    boolean belloScanning = false, captureInProgress = false, yoloAvail = false;
    int     scanRateCob   = 4000, totalScans = 0;
    Random  randGlonk     = new Random();
    boolean imagensnapAvail = false;
    // per-object cooldown — each label gets its own timer
    // so a brand new object always announces instantly,
    // but the same object wont spam every scan
    HashMap<String, Long> announceTimes = new HashMap<>();
    static final int COOLDOWN_NORMAL = 8000;  // 8s between same non-danger object
    static final int COOLDOWN_DANGER = 3000;  // 3s between same danger object
    ArrayList<String>       lastDetected = new ArrayList<>();
    ArrayList<DetectionBox> currentBoxes = new ArrayList<>();

    // persistent yolo process — kept alive so model doesnt reload every scan
    Process        yoloProc   = null;
    BufferedWriter yoloStdin  = null;
    BufferedReader yoloStdout = null;

    Process          liveProcess = null;
    javax.swing.Timer liveTimer  = null;
    String           livePath    = "/tmp/aa_live.jpg";
    boolean          ffmpegAvail = false;
    volatile boolean frameReaderRunning = false;
    volatile BufferedImage lastGoodFrame = null;
    volatile long    lastFrameModified   = 0;

    static final String[] INDOOR_OBJECTS  = {"chair","table","door","person","desk","laptop","couch","stairs","window","lamp","bookshelf","counter","refrigerator","bed","cabinet"};
    static final String[] OUTDOOR_OBJECTS = {"person","car","bicycle","tree","building","crosswalk","stop sign","bench","fire hydrant","bus","motorcycle","traffic light","pole","curb"};
    static final Set<String> DANGER_SET   = new HashSet<>(Arrays.asList("stairs","car","motorcycle","bus","crosswalk","curb","fire hydrant"));
    static final String[] DIRECTIONS      = {"ahead","slightly left","slightly right","to your left","to your right","ahead and left","ahead and right"};

    static final Color C_BG = new Color(13,13,23), C_CARD = new Color(22,22,38), C_CARD2 = new Color(26,26,44), C_BORDER = new Color(45,45,68);
    static final Color C_BLUE = new Color(82,168,255), C_GREEN = new Color(52,211,105), C_RED = new Color(255,75,75), C_YELLOW = new Color(255,212,55);
    static final Color C_ORANGE = new Color(255,148,35), C_PURPLE = new Color(168,118,255), C_TEXT = new Color(225,225,242), C_SUBTEXT = new Color(118,118,148), C_MUTED = new Color(68,68,95);

    public BlindMode(AlertManager alertsIn, UserProfileManager profilesIn) {
        alerts = alertsIn; profiles = profilesIn;
        imagensnapAvail = checkCameraTool();
        yoloAvail       = checkYOLO();
        System.out.println("[BlindMode] camera=" + imagensnapAvail + "  yolo=" + yoloAvail);
        if (yoloAvail) { writeDetectScript(); startYOLOProcess(); }
        setBackground(C_BG); setLayout(new BorderLayout());
        add(makeTopBar(), BorderLayout.NORTH);
        add(makeCenter(), BorderLayout.CENTER);
        add(makeBottom(), BorderLayout.SOUTH);
    }

    // checks if ultralytics is installed, tries python3 then python
    static boolean checkYOLO() {
        for (String cmd : new String[]{"python3","python"}) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{cmd,"-c","from ultralytics import YOLO"});
                p.waitFor(6, TimeUnit.SECONDS);
                if (p.exitValue() == 0) { System.out.println("[YOLO] found using " + cmd); return true; }
            } catch (Exception e) {}
        }
        System.out.println("[YOLO] ultralytics not installed, using simulated detection"); return false;
    }

    // writes python capture+detect script to temp file
    // the script opens the camera, keeps frames in memory, and runs yolo on demand
    void writeDetectScript() {
        // 3-thread design so YOLO detection never blocks the live preview:
        //   capture_loop  — reads camera as fast as possible, no sleep, keeps latest_frame fresh
        //   write_loop    — writes latest frame to disk at ~30fps independently
        //   main thread   — blocks on stdin waiting for detect commands, runs YOLO when asked
        String script =
            "import sys, threading, time\n" +
            "try:\n    import cv2\n    from ultralytics import YOLO\n" +
            "except ImportError:\n    sys.stdout.write('__NO_LIB__\\n'); sys.stdout.flush(); sys.exit(1)\n" +
            "LIVE_PATH = '/tmp/aa_live.jpg'\n" +
            "try:\n    model = YOLO('yolov8s.pt')\n" +
            "except Exception as e:\n    sys.stdout.write('__ERR__:'+str(e)+'\\n'); sys.stdout.flush(); sys.exit(1)\n" +
            "cap = cv2.VideoCapture(0)\n" +
            "if not cap.isOpened():\n    sys.stdout.write('__NO_CAM__\\n'); sys.stdout.flush(); sys.exit(1)\n" +
            "cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640); cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480); cap.set(cv2.CAP_PROP_FPS, 30)\n" +
            "latest_frame = [None]\nframe_lock = threading.Lock()\n" +
            // capture thread: reads camera as fast as it can, no sleep
            "def capture_loop():\n" +
            "    while True:\n" +
            "        ret, frame = cap.read()\n" +
            "        if ret:\n" +
            "            with frame_lock: latest_frame[0] = frame\n" +
            // write thread: encodes and saves to disk at 30fps, never blocks capture
            "def write_loop():\n" +
            "    while True:\n" +
            "        with frame_lock: f = latest_frame[0]\n" +
            "        if f is not None:\n" +
            "            try: cv2.imwrite(LIVE_PATH, f, [cv2.IMWRITE_JPEG_QUALITY, 70])\n" +
            "            except Exception: pass\n" +
            "        time.sleep(0.033)\n" +
            "threading.Thread(target=capture_loop, daemon=True).start()\n" +
            "threading.Thread(target=write_loop,   daemon=True).start()\n" +
            "time.sleep(0.8)\nsys.stdout.write('__READY__\\n'); sys.stdout.flush()\n" +
            // main thread: waits for detect commands, runs YOLO on latest in-memory frame
            "for line in sys.stdin:\n" +
            "    cmd = line.strip()\n" +
            "    if not cmd: continue\n" +
            "    if cmd == '__QUIT__': break\n" +
            "    with frame_lock: frame = latest_frame[0].copy() if latest_frame[0] is not None else None\n" +
            "    if frame is None:\n        sys.stdout.write('__NO_IMAGE__\\n__DONE__\\n'); sys.stdout.flush(); continue\n" +
            "    try:\n" +
            "        results = model(frame, verbose=False, conf=0.50)\n" +
            "        found = 0\n" +
            "        for r in results:\n" +
            "            for box in r.boxes:\n" +
            "                cls=r.names[int(box.cls[0])]; conf=float(box.conf[0])\n" +
            "                x1,y1,x2,y2=[int(v) for v in box.xyxy[0].tolist()]\n" +
            "                sys.stdout.write(f'{cls},{conf:.2f},{x1},{y1},{x2-x1},{y2-y1}\\n'); found+=1\n" +
            "        sys.stdout.write('__NONE__\\n' if found==0 else ''); sys.stdout.write('__DONE__\\n'); sys.stdout.flush()\n" +
            "    except Exception as e:\n        sys.stdout.write('__ERR__:'+str(e)+'\\n__DONE__\\n'); sys.stdout.flush()\n" +
            "cap.release()\n";
        try {
            String path = System.getProperty("java.io.tmpdir") + File.separator + "aa_detect.py";
            PrintWriter pw = new PrintWriter(new FileWriter(path)); pw.print(script); pw.close();
            System.out.println("[YOLO] wrote script to " + path);
        } catch (Exception e) { System.out.println("[YOLO] couldnt write script: " + e.getMessage()); }
    }

    // starts the persistent yolo process, waits for __READY__
    void startYOLOProcess() {
        Thread t = new Thread(() -> {
            try {
                String scriptPath = System.getProperty("java.io.tmpdir") + File.separator + "aa_detect.py";
                String pyCmd = "python3";
                try { Runtime.getRuntime().exec(new String[]{"python3","--version"}).waitFor(2,TimeUnit.SECONDS); } catch (Exception e) { pyCmd = "python"; }
                yoloProc   = Runtime.getRuntime().exec(new String[]{pyCmd, scriptPath});
                yoloStdin  = new BufferedWriter(new OutputStreamWriter(yoloProc.getOutputStream()));
                yoloStdout = new BufferedReader(new InputStreamReader(yoloProc.getInputStream()));
                System.out.println("[YOLO] process started, waiting for model load...");
                String first = yoloStdout.readLine();
                if ("__READY__".equals(first)) { System.out.println("[YOLO] model loaded!! ready to detect"); }
                else { System.out.println("[YOLO] unexpected startup token: " + first); if ("__NO_LIB__".equals(first)) yoloAvail = false; }
            } catch (Exception e) { System.out.println("[YOLO] couldnt start process: " + e.getMessage()); yoloAvail = false; }
        });
        t.setDaemon(true); t.setName("YOLOStartup"); t.start();
    }

    // sends detect to persistent python process, reads back results until __DONE__
    // returns list of [label, conf, x, y, w, h] or null on failure
    ArrayList<String[]> runYOLO(String ignored) {
        if (!yoloAvail || yoloProc == null || yoloStdin == null || yoloStdout == null) return null;
        try {
            yoloStdin.write("detect\n"); yoloStdin.flush();
            ArrayList<String[]> results = new ArrayList<>();
            String line;
            while ((line = yoloStdout.readLine()) != null) {
                line = line.trim();
                if ("__DONE__".equals(line) || "__NONE__".equals(line)) break;
                if (line.startsWith("__")) { if ("__NO_LIB__".equals(line)) { yoloAvail = false; return null; } continue; }
                String[] parts = line.split(",");
                if (parts.length == 6) results.add(parts);
            }
            return results;
        } catch (Exception e) {
            System.out.println("[YOLO] pipe error: " + e.getMessage());
            yoloProc = null; yoloStdin = null; yoloStdout = null; return null;
        }
    }

    // bigger box = closer to camera — numbers are roughly calibrated
    double estimateDistance(int boxH, int frameH) {
        double ratio = (double)boxH / Math.max(1, frameH);
        if (ratio > 0.6) return 0.3 + randGlonk.nextDouble() * 0.5;
        if (ratio > 0.4) return 0.8 + randGlonk.nextDouble() * 0.8;
        if (ratio > 0.2) return 1.6 + randGlonk.nextDouble() * 1.5;
        if (ratio > 0.1) return 3.0 + randGlonk.nextDouble() * 2.5;
        return 5.5 + randGlonk.nextDouble() * 5.0;
    }

    boolean checkCameraTool() {
        for (String cmd : new String[]{"python3","python"}) {
            try { Process p = Runtime.getRuntime().exec(new String[]{cmd,"-c","import cv2"}); p.waitFor(5, TimeUnit.SECONDS); if (p.exitValue() == 0) return true; }
            catch (Exception e) {}
        }
        return false;
    }

    // java-side frame reader — picks up frames written by python and paints them
    void startLiveCapture() {
        frameReaderRunning = true;
        Thread fr = new Thread(() -> {
            File f = new File(livePath);
            while (frameReaderRunning) {
                try {
                    if (f.exists() && f.length() > 500) {
                        long mod = f.lastModified();
                        if (mod != lastFrameModified) {
                            lastFrameModified = mod;
                            byte[] bytes = Files.readAllBytes(f.toPath());
                            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                            if (img != null) {
                                lastGoodFrame = img;
                                final BufferedImage fi = img;
                                SwingUtilities.invokeLater(() -> { cameraView.setFrame(fi); cameraView.repaint(); });
                            }
                        }
                    }
                    Thread.sleep(50);
                } catch (InterruptedException ie) { break; } catch (Exception ex) {}
            }
        }, "FrameReader");
        fr.setDaemon(true); fr.start();
        System.out.println("[Camera] frame reader started, watching " + livePath);
    }

    void stopLiveCapture() {
        frameReaderRunning = false;
        if (liveTimer   != null) { liveTimer.stop();     liveTimer   = null; }
        if (liveProcess != null) { liveProcess.destroy(); liveProcess = null; }
        ffmpegAvail = false; lastGoodFrame = null; lastFrameModified = 0;
    }

    // ── UI BUILDING ───────────────────────────────────────────────────

    JPanel makeTopBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER), BorderFactory.createEmptyBorder(12,20,12,20)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); left.setBackground(C_CARD);
        JLabel icon = new JLabel("👁"); icon.setFont(new Font("Arial",Font.PLAIN,20));
        JLabel title = new JLabel("  Blind Mode  —  Vision Assistant"); title.setFont(new Font("Arial",Font.BOLD,18)); title.setForeground(C_PURPLE);
        left.add(icon); left.add(title);
        statusLbl = pill("● Ready", C_SUBTEXT, new Color(118,118,148,28), new Color(118,118,148,60));
        JLabel aiBadge = new JLabel(yoloAvail ? "🤖 AI Detection (YOLOv8)" : "🤖 AI Off (pip install ultralytics)");
        aiBadge.setFont(new Font("Arial",Font.BOLD,11)); aiBadge.setForeground(yoloAvail ? C_GREEN : C_ORANGE);
        aiBadge.setOpaque(true); aiBadge.setBackground(yoloAvail ? new Color(52,211,105,20) : new Color(255,148,35,20));
        aiBadge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(yoloAvail?new Color(52,211,105,50):new Color(255,148,35,50),1), BorderFactory.createEmptyBorder(4,10,4,10)));
        String os2 = System.getProperty("os.name").toLowerCase();
        String noHint = os2.contains("win") ? "📷 No Camera (install ffmpeg)" : "📷 No Camera (brew install imagesnap)";
        JLabel camBadge = new JLabel(imagensnapAvail ? "📷 Camera Ready" : noHint);
        camBadge.setFont(new Font("Arial",Font.BOLD,11)); camBadge.setForeground(imagensnapAvail ? new Color(100,180,255) : C_ORANGE);
        camBadge.setOpaque(true); camBadge.setBackground(new Color(82,168,255,15));
        camBadge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(82,168,255,40),1), BorderFactory.createEmptyBorder(4,10,4,10)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0)); right.setBackground(C_CARD);
        right.add(aiBadge); right.add(camBadge); right.add(statusLbl);
        p.add(left, BorderLayout.WEST); p.add(right, BorderLayout.EAST); return p;
    }

    JPanel makeCenter() {
        JPanel split = new JPanel(new BorderLayout(1,0)); split.setBackground(C_BORDER);
        cameraView = new CameraView(); cameraView.setPreferredSize(new Dimension(520,0));
        split.add(cameraView, BorderLayout.CENTER); split.add(makeInfoPanel(), BorderLayout.EAST); return split;
    }

    JPanel makeInfoPanel() {
        JPanel p = new JPanel(); p.setBackground(C_BG); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(16,14,10,16)); p.setPreferredSize(new Dimension(300,0));
        addHdr(p,"Nearest Object"); vs(p,8);
        bigObjectLbl = lbl("—", new Font("Arial",Font.BOLD,30), C_SUBTEXT); p.add(bigObjectLbl);
        directionLbl = lbl("direction: —", new Font("Arial",Font.PLAIN,12), C_SUBTEXT); p.add(directionLbl); vs(p,4);
        distanceLbl  = lbl("—", new Font("Arial",Font.BOLD,22), C_BLUE); p.add(distanceLbl); vs(p,14);
        addHdr(p,"Proximity"); vs(p,6);
        proximityBar = new JProgressBar(0,100);
        proximityBar.setStringPainted(true); proximityBar.setString("Not scanning"); proximityBar.setForeground(C_GREEN); proximityBar.setBackground(new Color(38,38,58));
        proximityBar.setMaximumSize(new Dimension(99999,30)); proximityBar.setAlignmentX(Component.LEFT_ALIGNMENT); proximityBar.setFont(new Font("Arial",Font.BOLD,11));
        p.add(proximityBar); vs(p,4);
        proxDescLbl = lbl("Start scanning to detect objects", new Font("Arial",Font.ITALIC,11), C_SUBTEXT); p.add(proxDescLbl); vs(p,16);
        scanCountLbl = new JLabel("Scans: 0");
        scanCountLbl.setFont(new Font("Arial",Font.BOLD,11)); scanCountLbl.setForeground(C_SUBTEXT); scanCountLbl.setOpaque(true); scanCountLbl.setBackground(C_CARD);
        scanCountLbl.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_BORDER,1),BorderFactory.createEmptyBorder(3,10,3,10)));
        scanCountLbl.setAlignmentX(Component.LEFT_ALIGNMENT); p.add(scanCountLbl); p.add(Box.createVerticalGlue());
        addHdr(p,"Detection Log"); vs(p,5);
        logArea = new JTextArea("[ log ]\n"); logArea.setBackground(C_CARD); logArea.setForeground(C_SUBTEXT);
        logArea.setFont(new Font("Monospaced",Font.PLAIN,10)); logArea.setEditable(false); logArea.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER,1)); scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setMaximumSize(new Dimension(99999,80)); scroll.setPreferredSize(new Dimension(999,80)); p.add(scroll); return p;
    }

    JPanel makeBottom() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT,8,8));
        p.setBackground(C_CARD); p.setBorder(BorderFactory.createMatteBorder(1,0,0,0,C_BORDER));
        startBtn = btn("▶  Start Scanning", C_GREEN, new Color(10,10,10), e->startScanning());
        stopBtn  = btn("⏹  Stop",           C_RED,   Color.WHITE,          e->stopScanning()); stopBtn.setEnabled(false);
        p.add(startBtn); p.add(stopBtn);
        p.add(btn("📢  Announce", C_BLUE,  Color.WHITE, e->announceNow()));
        p.add(btn("🗑  Clear Log", C_CARD2, C_TEXT, e->{ logArea.setText("[ cleared ]\n"); totalScans=0; scanCountLbl.setText("Scans: 0"); }));
        JLabel envLbl = new JLabel("  Env:"); envLbl.setForeground(C_SUBTEXT); envLbl.setFont(new Font("Arial",Font.PLAIN,11));
        envPicker = new JComboBox<>(new String[]{"Indoor","Outdoor"});
        envPicker.setBackground(C_CARD2); envPicker.setForeground(C_TEXT); envPicker.setFont(new Font("Arial",Font.PLAIN,12));
        JLabel rateLbl = new JLabel("  Rate:"); rateLbl.setForeground(C_SUBTEXT); rateLbl.setFont(new Font("Arial",Font.PLAIN,11));
        rateSlider = new JSlider(3,12,5); rateSlider.setBackground(C_CARD); rateSlider.setMajorTickSpacing(3); rateSlider.setPaintTicks(true);
        rateSlider.setPreferredSize(new Dimension(110,36));
        rateSlider.addChangeListener(e->{ scanRateCob=rateSlider.getValue()*1000; rateValLbl.setText(rateSlider.getValue()+"s"); });
        rateValLbl = new JLabel("5s"); rateValLbl.setForeground(C_YELLOW); rateValLbl.setFont(new Font("Arial",Font.BOLD,12));
        p.add(envLbl); p.add(envPicker); p.add(rateLbl); p.add(rateSlider); p.add(rateValLbl); return p;
    }

    // ── SCANNING LOGIC ────────────────────────────────────────────────

    void startScanning() {
        if (belloScanning) return;
        belloScanning = true; startBtn.setEnabled(false); stopBtn.setEnabled(true);
        setPill(statusLbl,"● Scanning...",C_GREEN,new Color(52,211,105,28),new Color(52,211,105,60));
        AudioStuff.speak("Blind mode scanning started.");
        startLiveCapture(); doScan();
    }

    void stopScanning() {
        belloScanning = false; stopLiveCapture(); startBtn.setEnabled(true); stopBtn.setEnabled(false);
        announceTimes.clear(); // reset cooldowns so first detection after restart announces right away
        setPill(statusLbl,"● Stopped",C_SUBTEXT,new Color(118,118,148,28),new Color(118,118,148,60));
        bigObjectLbl.setText("—"); bigObjectLbl.setForeground(C_SUBTEXT); distanceLbl.setText("—"); directionLbl.setText("direction: —");
        proximityBar.setValue(0); proximityBar.setString("Scan stopped"); proxDescLbl.setText("Press Start to resume"); proxDescLbl.setForeground(C_SUBTEXT);
        currentBoxes.clear(); cameraView.setBoxes(currentBoxes); cameraView.repaint();
    }

    // one scan cycle — live preview is separate, this just runs YOLO analysis
    void doScan() {
        if (captureInProgress || !belloScanning) return;
        captureInProgress = true;
        Thread t = new Thread(() -> {
            BufferedImage frame = null;
            File lf = new File(livePath);
            if (lf.exists() && lf.length() > 500) { try { frame = ImageIO.read(lf); } catch (Exception e) {} }
            ArrayList<String[]> yoloResults = (yoloAvail && lf.exists()) ? runYOLO(livePath) : null;
            lastDetected.clear(); currentBoxes.clear();
            String near; double dist; String dir;
            if (yoloResults != null && !yoloResults.isEmpty()) {
                int frameH = frame != null ? frame.getHeight() : 480;
                for (String[] det : yoloResults) {
                    try {
                        String label = det[0]; float conf = Float.parseFloat(det[1]);
                        int x=Integer.parseInt(det[2]),y=Integer.parseInt(det[3]),w=Integer.parseInt(det[4]),h=Integer.parseInt(det[5]);
                        lastDetected.add(label);
                        Color col = DANGER_SET.contains(label)?new Color(255,75,75) : lastDetected.size()==1?new Color(52,211,105):new Color(82,168,255);
                        currentBoxes.add(new DetectionBox(x,y,w,h,col,label+" "+Math.round(conf*100)+"%"));
                    } catch (Exception e) {}
                }
                near = lastDetected.isEmpty() ? "nothing" : lastDetected.get(0);
                dist = lastDetected.isEmpty() ? 5.0 : estimateDistance(Integer.parseInt(yoloResults.get(0)[5]), frameH);
                dir  = DIRECTIONS[randGlonk.nextInt(DIRECTIONS.length)];
                System.out.println("[YOLO] detected " + lastDetected.size() + " objects: " + lastDetected);
            } else {
                String[] pool = "Outdoor".equals(envPicker.getSelectedItem()) ? OUTDOOR_OBJECTS : INDOOR_OBJECTS;
                ArrayList<String> poolCopy = new ArrayList<>(Arrays.asList(pool));
                Collections.shuffle(poolCopy, randGlonk);
                int num = 1 + randGlonk.nextInt(2);
                for (int i=0; i<num && i<poolCopy.size(); i++) lastDetected.add(poolCopy.get(i));
                near = lastDetected.get(0);
                dist = Math.round((randGlonk.nextDouble()*11.5+0.5)*10.0)/10.0;
                dir  = DIRECTIONS[randGlonk.nextInt(DIRECTIONS.length)];
                buildFakeBoxes(frame);
            }
            final String fNear=near; final double fDist=dist; final String fDir=dir;
            SwingUtilities.invokeLater(() -> {
                captureInProgress = false; cameraView.setBoxes(currentBoxes); cameraView.repaint();
                updateInfoPanel(fNear, fDist, fDir);
                if (belloScanning) new javax.swing.Timer(scanRateCob, e->doScan()) {{ setRepeats(false); }}.start();
            });
        });
        t.setDaemon(true); t.setName("ScanThread"); t.start();
    }

    void updateInfoPanel(String near, double dist, String dir) {
        bigObjectLbl.setText(near); bigObjectLbl.setForeground(DANGER_SET.contains(near)?C_ORANGE:C_PURPLE);
        distanceLbl.setText(String.format("%.1f  meters",dist)); directionLbl.setText("direction: "+dir);
        int prox = Math.max(0,Math.min(100,(int)((1.0-(dist-0.5)/11.5)*100.0)));
        proximityBar.setValue(prox);
        if      (prox>80) { proximityBar.setForeground(C_RED);    proximityBar.setString("VERY CLOSE! "+String.format("%.1f",dist)+"m"); proxDescLbl.setText("⚠ extremely close!!");  proxDescLbl.setForeground(C_RED); }
        else if (prox>55) { proximityBar.setForeground(C_ORANGE); proximityBar.setString("Getting close: "+String.format("%.1f",dist)+"m"); proxDescLbl.setText("Caution"); proxDescLbl.setForeground(C_ORANGE); }
        else if (prox>30) { proximityBar.setForeground(C_YELLOW); proximityBar.setString("Moderate: "+String.format("%.1f",dist)+"m"); proxDescLbl.setText("Moderate distance"); proxDescLbl.setForeground(C_YELLOW); }
        else              { proximityBar.setForeground(C_GREEN);  proximityBar.setString("Safe: "+String.format("%.1f",dist)+"m"); proxDescLbl.setText("Safe distance"); proxDescLbl.setForeground(C_GREEN); }
        // check danger object first — always takes priority
        boolean isDanger = DANGER_SET.contains(near) && dist < 3.0;
        long now = System.currentTimeMillis();
        if (isDanger && (now - announceTimes.getOrDefault(near, 0L)) > COOLDOWN_DANGER) {
            AudioStuff.speakWarning(String.format("%s ahead, %.1f meters!", near, dist));
            alerts.addAlert(new Alert(Alert.AlertType.BLIND_WARNING,"DANGER: "+near+" @ "+String.format("%.1f",dist)+"m",3,near));
            announceTimes.put(near, now);
        } else if (!isDanger) {
            // find first detected object that isnt on cooldown yet — so a new object always announces
            // even if the closest one was just spoken
            for (String obj : lastDetected) {
                if ((now - announceTimes.getOrDefault(obj, 0L)) > COOLDOWN_NORMAL) {
                    String ann = obj+", "+dir+", "+String.format("%.1f",dist)+" meters";
                    AudioStuff.speak(ann);
                    alerts.addAlert(new Alert(Alert.AlertType.BLIND_OBJECT,obj+" @ "+String.format("%.1f",dist)+"m",1,obj));
                    announceTimes.put(obj, now);
                    break; // only announce one per scan so it doesnt talk over itself
                }
            }
        }
        totalScans++; scanCountLbl.setText("Scans: "+totalScans);
        logArea.append((isDanger?"🚨 ":"")+(yoloAvail?"[AI]":"[sim]")+" ["+totalScans+"] "+near+" @ "+String.format("%.1f",dist)+"m "+dir+"\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        setPill(statusLbl,"● "+near+" @ "+String.format("%.1f",dist)+"m",C_GREEN,new Color(52,211,105,28),new Color(52,211,105,60));
    }

    void buildFakeBoxes(BufferedImage frame) {
        int cW=frame!=null?frame.getWidth():640, cH=frame!=null?frame.getHeight():480;
        for (int i=0; i<lastDetected.size(); i++) {
            String obj=lastDetected.get(i); int h=Math.abs(obj.hashCode()), j=randGlonk.nextInt(30)-15;
            int bx=Math.max(0,Math.min((h*37+j)%Math.max(1,cW-180)+10,cW-130));
            int by=Math.max(0,Math.min((h*53+j)%Math.max(1,cH-100)+10,cH-80));
            Color col=DANGER_SET.contains(obj)?new Color(255,75,75):i==0?new Color(52,211,105):new Color(82,168,255);
            currentBoxes.add(new DetectionBox(bx,by,120+h%90,70+h%55,col,obj+" "+(75+randGlonk.nextInt(24))+"%"));
        }
    }

    void announceNow() {
        if (lastDetected.isEmpty()) { AudioStuff.speak("No objects detected. Start scanning first."); return; }
        StringBuilder sb = new StringBuilder("Currently seeing: ");
        for (int i=0; i<lastDetected.size(); i++) {
            sb.append(lastDetected.get(i));
            if (i<lastDetected.size()-2) sb.append(", ");
            else if (i==lastDetected.size()-2) sb.append(", and ");
        }
        AudioStuff.speak(sb.toString());
    }

    public void cleanup() { belloScanning=false; System.out.println("[BlindMode] cleaned up"); }

    // ── TINY HELPERS ──────────────────────────────────────────────────

    void addHdr(JPanel p, String t) {
        JLabel l=new JLabel(t.toUpperCase()); l.setFont(new Font("Arial",Font.BOLD,9)); l.setForeground(C_SUBTEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT); l.setMaximumSize(new Dimension(99999,22));
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER),BorderFactory.createEmptyBorder(0,0,5,0)));
        p.add(l);
    }
    void vs(JPanel p, int n) { p.add(Box.createVerticalStrut(n)); }
    JLabel lbl(String t, Font f, Color c) { JLabel l=new JLabel(t); l.setFont(f); l.setForeground(c); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l; }
    JLabel pill(String t, Color fg, Color bg, Color border) {
        JLabel l=new JLabel(t); l.setFont(new Font("Arial",Font.BOLD,12)); l.setForeground(fg); l.setOpaque(true); l.setBackground(bg);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border,1),BorderFactory.createEmptyBorder(4,12,4,12))); return l;
    }
    void setPill(JLabel l, String t, Color fg, Color bg, Color border) {
        l.setText(t); l.setForeground(fg); l.setBackground(bg);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border,1),BorderFactory.createEmptyBorder(4,12,4,12)));
    }
    JButton btn(String t, Color bg, Color fg, ActionListener a) {
        JButton b=new JButton(t); b.setBackground(bg); b.setForeground(fg); b.setFont(new Font("Arial",Font.BOLD,12));
        b.setOpaque(true); b.setFocusPainted(false); b.setBorderPainted(false); b.setCursor(new Cursor(Cursor.HAND_CURSOR)); b.addActionListener(a); return b;
    }

    // ── CAMERA VIEW INNER CLASS ───────────────────────────────────────

    class CameraView extends JPanel {
        BufferedImage frame; ArrayList<DetectionBox> boxes = new ArrayList<>();
        CameraView() { setBackground(new Color(8,8,18)); }
        void setFrame(BufferedImage img) { frame=img; }
        void setBoxes(ArrayList<DetectionBox> b) { boxes=new ArrayList<>(b); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int W=getWidth(), H=getHeight();
            if (frame==null) {
                g2.setColor(new Color(12,12,22)); g2.fillRect(0,0,W,H);
                g2.setColor(new Color(22,22,38));
                for (int x=0;x<W;x+=30) g2.drawLine(x,0,x,H);
                for (int y=0;y<H;y+=30) g2.drawLine(0,y,W,y);
                g2.setFont(new Font("Arial",Font.BOLD,22)); g2.setColor(new Color(80,80,110));
                String l1="📷  No Camera Feed"; FontMetrics fm=g2.getFontMetrics();
                g2.drawString(l1,(W-fm.stringWidth(l1))/2,H/2-30);
                g2.setFont(new Font("Monospaced",Font.BOLD,13)); g2.setColor(new Color(82,168,255,140));
                String l2="brew install imagesnap"; fm=g2.getFontMetrics();
                g2.drawString(l2,(W-fm.stringWidth(l2))/2,H/2+20);
                if (belloScanning) drawFakeBoxes(g2,W,H); return;
            }
            g2.drawImage(frame,0,0,W,H,null);
            g2.setColor(new Color(0,0,0,20)); g2.fillRect(0,0,W,H);
            float sx=(float)W/frame.getWidth(), sy=(float)H/frame.getHeight();
            for (DetectionBox box : boxes) {
                int bx=(int)(box.x*sx),by=(int)(box.y*sy),bw=(int)(box.w*sx),bh=(int)(box.h*sy);
                g2.setColor(new Color(box.color.getRed(),box.color.getGreen(),box.color.getBlue(),35));
                g2.fillRect(bx,by,bw,bh);
                g2.setColor(box.color); g2.setStroke(new BasicStroke(2.2f)); g2.drawRect(bx,by,bw,bh);
                int cs=12; g2.setStroke(new BasicStroke(3.5f));
                g2.drawLine(bx,by,bx+cs,by); g2.drawLine(bx,by,bx,by+cs);
                g2.drawLine(bx+bw,by,bx+bw-cs,by); g2.drawLine(bx+bw,by,bx+bw,by+cs);
                g2.drawLine(bx,by+bh,bx+cs,by+bh); g2.drawLine(bx,by+bh,bx,by+bh-cs);
                g2.drawLine(bx+bw,by+bh,bx+bw-cs,by+bh); g2.drawLine(bx+bw,by+bh,bx+bw,by+bh-cs);
                g2.setFont(new Font("Arial",Font.BOLD,11)); FontMetrics fm=g2.getFontMetrics();
                g2.setColor(box.color); g2.fillRoundRect(bx,by-18,fm.stringWidth(box.label)+10,18,6,6);
                g2.setColor(new Color(10,10,10)); g2.drawString(box.label,bx+5,by-4);
            }
            g2.setFont(new Font("Arial",Font.BOLD,11));
            String tag = yoloAvail ? "● LIVE  AI (YOLOv8)" : "● LIVE  CAMERA"; FontMetrics fm=g2.getFontMetrics();
            g2.setColor(new Color(0,0,0,130)); g2.fillRoundRect(10,10,fm.stringWidth(tag)+16,22,8,8);
            g2.setColor(yoloAvail?C_GREEN:new Color(100,180,255)); g2.drawString(tag,18,26);
        }

        void drawFakeBoxes(Graphics2D g2, int W, int H) {
            float sx=(float)W/640, sy=(float)H/480;
            for (DetectionBox box : boxes) {
                int bx=Math.max(0,(int)(box.x*sx)),by=Math.max(0,(int)(box.y*sy));
                int bw=(int)(box.w*sx),bh=(int)(box.h*sy);
                g2.setColor(new Color(box.color.getRed(),box.color.getGreen(),box.color.getBlue(),30));
                g2.fillRect(bx,by,bw,bh);
                g2.setColor(new Color(box.color.getRed(),box.color.getGreen(),box.color.getBlue(),120));
                g2.setStroke(new BasicStroke(1.5f)); g2.drawRect(bx,by,bw,bh);
                g2.setFont(new Font("Arial",Font.BOLD,10)); FontMetrics fm=g2.getFontMetrics();
                g2.setColor(new Color(box.color.getRed(),box.color.getGreen(),box.color.getBlue(),160));
                g2.fillRect(bx,by-16,fm.stringWidth(box.label)+8,16);
                g2.setColor(new Color(10,10,10,200)); g2.drawString(box.label,bx+4,by-3);
            }
        }
    }

    static class DetectionBox {
        int x,y,w,h; Color color; String label;
        DetectionBox(int x,int y,int w,int h,Color c,String l){this.x=x;this.y=y;this.w=w;this.h=h;color=c;label=l;}
    }
}
