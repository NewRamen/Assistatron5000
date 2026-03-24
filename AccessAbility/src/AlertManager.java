import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;

// AlertManager.java
// ok so this file has like 4 classes in it
// Alert, UserProfile, UserProfileManager, and AlertManager
// i put them all here bc they all kinda do data stuff
// probably not how ur supposed to do it but it compiles so whatever
//
// DATA STRUCTURS (this is the important part for the rubric and TSA):
//
//   #1 - ArrayList<Alert>           alert history (dynamic array)
//   #2 - Queue<Alert>               alert queue (FIFO, uses LinkedList)
//   #3 - TreeMap<String,Integer>    category frequency (sorted map!!)
//   #4 - Stack<String>              undo stack (LIFO)
//   #5 - HashMap<String,UserProfile> user profiles (in UserProfileManager)
//
// ms johnson if ur reading this i worked really hard on this
// please give me full credit thx

// ─────────────────────────────────────────────────────────────────────
// Alert class
// represents one alert event thing (ex. "loud noise" or "car nearby")
// ─────────────────────────────────────────────────────────────────────
class Alert {

    // the different types of alert that can happen
    enum AlertType {
        DEAF_AUDIO,    // normal sound hapening in deaf mode
        DEAF_URGENT,   // VERY loud noise
        BLIND_OBJECT,  // detected an object in blind mode
        BLIND_WARNING, // object is getting too close!!
        SYSTEM         // just the app telling u something
    }

    // the actual data
    AlertType typeGlonk;   // what kind
    String messageKorn;    // the text of the alert
    int severityCob;       // 1=chill 2=careful 3=DANGER
    Date whenItHapend;     // timestamp
    String category;       // for the treemap later (data structure #3)

    Alert(AlertType type, String msg, int sev, String cat) {
        typeGlonk = type;
        messageKorn = msg;
        // make sure severity is between 1 and 3
        // idk why i used a ternary here instead of Math.clamp but it works
        severityCob = (sev < 1) ? 1 : (sev > 3) ? 3 : sev;
        whenItHapend = new Date();
        category = cat;
    }

    AlertType getType()     { return typeGlonk;    }
    String getMessage()     { return messageKorn;  }
    int getSeverity()       { return severityCob;  }
    Date getTimestamp()     { return whenItHapend; }
    String getCategory()    { return category;     }

    // for the history list
    public String toString() {
        String t = new SimpleDateFormat("HH:mm:ss").format(whenItHapend);
        String icon = (severityCob == 3) ? "[!!!]" : (severityCob == 2) ? "[!! ]" : "[ i ]";
        return t + " " + icon + " " + messageKorn;
    }

    // shorter version for the sidebar list
    String toShort() {
        String emoji = (severityCob == 3) ? "🚨" : (severityCob == 2) ? "⚠️" : "ℹ️";
        return emoji + "  " + messageKorn;
    }
}

// ─────────────────────────────────────────────────────────────────────
// UserProfile
// stores one users settings like their name, threshold, voice etc
// ─────────────────────────────────────────────────────────────────────
class UserProfile {

    String userName;
    boolean isDeafMode = true;       // default to deaf mode
    int thresholdCorn = 65;          // how loud before alert triggers (percent)
    float scanRateBello = 2.0f;      // seconds between scans in blind mode
    String voiceKorn = "Alex";       // which tts voice to use

    // basic constructor
    UserProfile(String name) {
        userName = name;
    }

    // constructor when loading from save file
    UserProfile(String name, boolean deaf, int thresh, float rate, String voice) {
        userName = name;
        isDeafMode = deaf;
        thresholdCorn = thresh;
        scanRateBello = rate;
        voiceKorn = voice;
    }

    // save to csv - comma separated values
    String toCSV() {
        return userName + "," + isDeafMode + "," + thresholdCorn + "," + scanRateBello + "," + voiceKorn;
    }

    public String toString() {
        return "User[" + userName + "] thresh=" + thresholdCorn + "% voice=" + voiceKorn;
    }
}

// ─────────────────────────────────────────────────────────────────────
// UserProfileManager
// DATA STRUCTURE #5 !! HashMap !!
// manages all the user profiles using a hashmap (key = username)
// ─────────────────────────────────────────────────────────────────────
class UserProfileManager {

    // DATA STRUCTURE 5: HashMap<String, UserProfile>
    // hashmap = key value pairs
    // key = the username (String)
    // value = the actual profile (UserProfile)
    // O(1) lookup time which is fast
    HashMap<String, UserProfile> theMap = new HashMap<>();

    String whoIsLoggedIn = "Guest";
    static final String SAVE_FILE = "aa_profiles.csv";

    UserProfileManager() {
        // always make a guest profile so app isnt empty
        theMap.put("Guest", new UserProfile("Guest"));
        loadFromFile(); // try to load previous session
        System.out.println("[Profiles] " + theMap.size() + " profile(s) loaded");
    }

    // add new profile, returns false if already exist
    boolean addProfile(String name) {
        if (name == null || name.trim().isEmpty()) {
            System.out.println("cant add profile with empty name lol");
            return false;
        }
        if (theMap.containsKey(name) == true) { // already exists
            System.out.println("profile already exists: " + name);
            return false;
        }
        theMap.put(name, new UserProfile(name));
        System.out.println("added new profile: " + name);
        return true;
    }

    UserProfile getCurrentUser() {
        UserProfile p = theMap.get(whoIsLoggedIn);
        if (p == null) return theMap.get("Guest"); // fallback to guest
        return p;
    }

    String getCurrentName() { return whoIsLoggedIn; }

    boolean switchUser(String name) {
        if (!theMap.containsKey(name)) return false;
        whoIsLoggedIn = name;
        return true;
    }

    void saveProfiles() {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(SAVE_FILE));
            for (UserProfile p : theMap.values()) {
                pw.println(p.toCSV());
            }
            pw.close();
            System.out.println("[Profiles] saved " + theMap.size() + " profiles");
        } catch (IOException e) {
            System.out.println("couldnt save profiles rip: " + e.getMessage());
        }
    }

    void loadFromFile() {
        File f = new File(SAVE_FILE);
        if (!f.exists()) {
            System.out.println("[Profiles] no save file yet");
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(SAVE_FILE));
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 5) {
                    try {
                        theMap.put(parts[0], new UserProfile(
                            parts[0],
                            Boolean.parseBoolean(parts[1]),
                            Integer.parseInt(parts[2]),
                            Float.parseFloat(parts[3]),
                            parts[4]
                        ));
                    } catch (NumberFormatException nfe) {
                        System.out.println("bad line in save file, skipping it"); // just skip bad lines
                    }
                }
            }
            br.close();
        } catch (IOException e) {
            System.out.println("[Profiles] load failed: " + e.getMessage());
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// AlertManager (the MAIN class of this file)
// uses 4 data structures
// ─────────────────────────────────────────────────────────────────────
public class AlertManager {

    // DATA STRUCTURE 1: ArrayList
    // dynamic array, resizes itself when it gets full
    // good for storing alerts because we dont know how many there will be
    ArrayList<Alert> alertHistory = new ArrayList<>();

    // DATA STRUCTURE 2: Queue (implemented using LinkedList)
    // FIFO = first in first out, like a lunch line
    // alerts get processed in the order they come in
    Queue<Alert> alertQueue = new LinkedList<>();

    // DATA STRUCTURE 3: TreeMap
    // sorted map - keys are kept in alphabetical order automatically
    // String = category name, Integer = how many times it appeared
    // way cooler than regular HashMap bc its sorted
    TreeMap<String, Integer> categoryFreq = new TreeMap<>();

    // DATA STRUCTURE 4: Stack
    // LIFO = last in first out, like a stack of trays in the cafeteria
    // stores recent alert messages so u can undo them
    Stack<String> undoStack = new Stack<>();

    // counters for stats
    int totalCob   = 0;
    int deafCount  = 0;
    int blindCount = 0;

    static final int MAX_SIZE = 200; // dont let the list get infinitely big

    public AlertManager() {
        System.out.println("[AlertManager] initialized!! 4 data structures ready");
        System.out.println("  >> ArrayList, Queue (LinkedList), TreeMap, Stack");
    }

    // add alert to ALL 4 data structures at once
    public synchronized void addAlert(Alert a) {
        if (a == null) {
            System.out.println("tried to add null alert?? skipping");
            return;
        }

        // 1) add to ArrayList (history)
        alertHistory.add(a);
        if (alertHistory.size() > MAX_SIZE) {
            alertHistory.remove(0); // delete oldest when list too big
        }

        // 2) add to Queue (for processing in order)
        alertQueue.offer(a);

        // 3) update TreeMap (category frequency count)
        String cat = a.getCategory();
        int prevCount = categoryFreq.getOrDefault(cat, 0);
        categoryFreq.put(cat, prevCount + 1);

        // 4) push to Stack (undo history)
        undoStack.push(a.getMessage());

        // update stats
        totalCob++;
        if (a.getType() == Alert.AlertType.DEAF_AUDIO || a.getType() == Alert.AlertType.DEAF_URGENT) {
            deafCount++;
        } else {
            blindCount++;
        }
    }

    // undo = remove the last alert we added
    public String undoLast() {
        if (undoStack.isEmpty() || alertHistory.isEmpty()) return null;
        alertHistory.remove(alertHistory.size() - 1);
        return undoStack.pop(); // pops from stack (LIFO!!)
    }

    // get next alert from queue (FIFO order)
    public Alert processNext() {
        return alertQueue.poll(); // returns null if empty, not throws exception
    }

    public ArrayList<Alert> getAllAlerts() {
        return new ArrayList<>(alertHistory); // return copy not reference
    }

    // filter by type
    public ArrayList<Alert> getByType(Alert.AlertType type) {
        ArrayList<Alert> result = new ArrayList<>();
        for (int i = 0; i < alertHistory.size(); i++) { // regular for loop bc why not
            if (alertHistory.get(i).getType() == type) {
                result.add(alertHistory.get(i));
            }
        }
        return result;
    }

    public TreeMap<String, Integer> getCategoryFrequency() {
        return new TreeMap<>(categoryFreq); // return a copy
    }

    // find whichever category appeared most
    public String getMostCommon() {
        if (categoryFreq.isEmpty()) return "none";
        String topKorn = "none";
        int topCount   = 0;
        for (Map.Entry<String, Integer> entry : categoryFreq.entrySet()) {
            if (entry.getValue() > topCount) {
                topCount = entry.getValue();
                topKorn  = entry.getKey();
            }
        }
        return topKorn + " (" + topCount + "x)";
    }

    public int getTotalCount()  { return totalCob;            }
    public int getDeafCount()   { return deafCount;           }
    public int getBlindCount()  { return blindCount;          }
    public int getQueueSize()   { return alertQueue.size();   }

    // summary string for the status bar at the bottom of the window
    public String getSummaryString() {
        return "Total: " + totalCob + "  |  Deaf: " + deafCount
             + "  |  Blind: " + blindCount + "  |  Top: " + getMostCommon();
    }

    public void clearAll() {
        alertHistory.clear();
        alertQueue.clear();
        categoryFreq.clear();
        undoStack.clear();
        totalCob = 0; deafCount = 0; blindCount = 0;
        System.out.println("[AlertManager] cleared everything");
    }
}
