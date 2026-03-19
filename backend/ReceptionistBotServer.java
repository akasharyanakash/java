import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReceptionistBotServer {

    private static final int PORT = 8080;

    private static final String DATA_DIR = "data";
    private static final String ADMIN_FILE = DATA_DIR + File.separator + "admin.txt";
    private static final String LEADS_FILE = DATA_DIR + File.separator + "leads.csv";
    private static final String INQUIRIES_FILE = DATA_DIR + File.separator + "inquiries.csv";
    private static final String CHATLOG_FILE = DATA_DIR + File.separator + "chatlog.txt";
    private static final String DELETED_FILE = DATA_DIR + File.separator + "deleted.csv";

    private static final Map<String, UserSession> sessions = new HashMap<>();

    public static void main(String[] args) throws Exception {
        setupFiles();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/chat", ReceptionistBotServer::handleChat);
        server.createContext("/inquiry", ReceptionistBotServer::handleInquiry);
        server.createContext("/login", ReceptionistBotServer::handleLogin);
        server.createContext("/change-password", ReceptionistBotServer::handleChangePassword);

        server.createContext("/leads", ReceptionistBotServer::handleGetLeads);
        server.createContext("/inquiries", ReceptionistBotServer::handleGetInquiries);
        server.createContext("/chatlog", ReceptionistBotServer::handleGetChatlog);
        server.createContext("/deleted", ReceptionistBotServer::handleGetDeleted);
        server.createContext("/course-stats", ReceptionistBotServer::handleCourseStats);

        server.createContext("/update-lead", ReceptionistBotServer::handleUpdateLead);
        server.createContext("/delete-lead", ReceptionistBotServer::handleDeleteLead);
        server.createContext("/update-inquiry", ReceptionistBotServer::handleUpdateInquiry);
        server.createContext("/delete-inquiry", ReceptionistBotServer::handleDeleteInquiry);

        server.createContext("/restore-deleted", ReceptionistBotServer::handleRestoreDeleted);
        server.createContext("/delete-deleted-permanent", ReceptionistBotServer::handleDeleteDeletedPermanent);

        server.setExecutor(null);
        System.out.println("Receptionist Bot Server started at http://127.0.0.1:" + PORT);
        server.start();
    }

    // =========================
    // Setup
    // =========================
    private static void setupFiles() throws IOException {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        createIfMissing(ADMIN_FILE, "admin,1234");
        createIfMissing(LEADS_FILE, "DateTime,Name,Course,Contact,Status\n");
        createIfMissing(INQUIRIES_FILE, "DateTime,Name,Email,Phone,Course,Message\n");
        createIfMissing(CHATLOG_FILE, "");
        createIfMissing(DELETED_FILE, "Type,DeletedAt,OriginalRow,Data\n");
    }

    private static void createIfMissing(String filePath, String defaultContent) throws IOException {
        File f = new File(filePath);
        if (!f.exists()) {
            Files.writeString(f.toPath(), defaultContent, StandardCharsets.UTF_8);
        }
    }

    // =========================
    // Chat API
    // =========================
    private static void handleChat(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"reply\":\"Only POST allowed.\",\"options\":[]}");
            return;
        }

        try {
            String body = readBody(ex);
            String clientId = opt(getJsonValue(body, "clientId"), "guest");
            String message = opt(getJsonValue(body, "message"), "").trim();

            if (message.isEmpty()) {
                sendJson(ex, 400, "{\"reply\":\"Please koi message likhiye.\",\"options\":[]}");
                return;
            }

            UserSession session = sessions.computeIfAbsent(clientId, k -> new UserSession());
            BotResponse response = generateReply(session, message);

            appendChat(clientId, message, response.reply);
            sendJson(ex, 200, buildReplyJson(response.reply, response.options));

        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, "{\"reply\":\"Sorry, abhi server reply nahi de pa raha.\",\"options\":[]}");
        }
    }

    private static BotResponse generateReply(UserSession session, String message) {
        String msg = message.trim();

        if (session.name == null) {
            if (looksLikeName(msg) && !containsAny(msg.toLowerCase(), "bca", "bba", "fees", "admission", "contact")) {
                session.name = capitalize(msg);
                return new BotResponse(
                        pick(
                                "Nice to meet you, " + session.name + " 😊\nAap BCA lena chahte hain ya BBA?",
                                "Thank you, " + session.name + ".\nBatayiye, aapka interest BCA me hai ya BBA me?",
                                session.name + ", achha laga.\nPlease course choose kijiye: BCA ya BBA."
                        ),
                        new String[]{"BCA", "BBA"}
                );
            }

            return new BotResponse(
                    pick(
                            "Namaste! Main My College ka Receptionist Bot hoon.\nSabse pehle apna naam batayiye.",
                            "Hello! Main aapki help ke liye yahan hoon.\nStart karne ke liye apna naam bata dijiye.",
                            "Namaste! Pehle apna naam share kijiye, phir main aapko guide karunga."
                    ),
                    new String[]{"Akash", "Rahul", "Priya"}
            );
        }

        if (session.course == null) {
            String course = detectCourse(msg);
            if (course != null) {
                session.course = course;
                return new BotResponse(
                        pick(
                                "Great! Aapne " + session.course + " choose kiya hai.\nAb apna phone number ya email bhejiye.",
                                "Theek hai, " + session.course + " note kar liya gaya hai.\nAb contact detail share kijiye.",
                                "Achha choice hai.\nPlease phone number ya email bhej dijiye."
                        ),
                        new String[]{"9876543210", "student@gmail.com"}
                );
            }

            return new BotResponse(
                    "Please course choose kijiye: BCA ya BBA.",
                    new String[]{"BCA", "BBA"}
            );
        }

        if (session.contact == null) {
            if (looksLikePhone(msg) || looksLikeEmail(msg)) {
                session.contact = msg;
                session.status = "Complete";

                try {
                    appendCsvRow(LEADS_FILE, new String[]{
                            LocalDateTime.now().toString(),
                            session.name,
                            session.course,
                            session.contact,
                            session.status
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return new BotResponse(
                        pick(
                                "Thank you, " + session.name + "! Aapki details save ho gayi hain.\nAb aap fees, admission, documents ya contact details pooch sakte hain.",
                                "Done! Inquiry save ho gayi hai.\nAb aap college se related sawaal pooch sakte hain.",
                                "Perfect, details record ho gayi hain.\nAb jo poochna ho pooch sakte hain."
                        ),
                        new String[]{"fees", "admission", "documents", "contact"}
                );
            }

            return new BotResponse(
                    "Please valid phone number ya email bhejiye, tabhi inquiry complete hogi.",
                    new String[]{"9876543210", "student@gmail.com"}
            );
        }

        return new BotResponse(topicReply(msg), suggestOptions(msg));
    }

    private static String topicReply(String msg) {
        String lower = msg.toLowerCase().trim();

        if (containsAny(lower, "hi", "hello", "hey", "namaste", "namaskar")) {
            return pick(
                    "Namaste! Aap BCA, BBA, fees, admission, documents ya contact details ke baare me pooch sakte hain.",
                    "Hello! Main college se related basic information dene ke liye yahan hoon.",
                    "Namaste! Bataiye, aapko course, fees ya admission me kis cheez ki help chahiye?"
            );
        }

        if (containsAny(lower, "bca")) {
            return pick(
                    "BCA ek 3 saal ka undergraduate course hai. Isme programming, database aur computer applications jaise subjects hote hain.",
                    "BCA un students ke liye achha course hai jinko computer field me interest ho.",
                    "Agar aap software ya IT side me jana chahte hain, to BCA ek strong option hai."
            );
        }

        if (containsAny(lower, "bba")) {
            return pick(
                    "BBA ek 3 saal ka management course hai. Isme business, management aur communication par focus hota hai.",
                    "BBA business aur management field ke liye useful course hai.",
                    "Agar aap management side me career banana chahte hain, to BBA achha option hai."
            );
        }

        if (containsAny(lower, "course", "courses")) {
            return "Hamare college me BCA aur BBA courses available hain.";
        }

        if (containsAny(lower, "fees", "fee")) {
            return "Fees course ke hisaab se hoti hai. Exact fee structure ke liye admission office se confirm karna best rahega.";
        }

        if (containsAny(lower, "admission", "admissions")) {
            return "Admission process open hai. Aap office me 10:00 AM se 3:00 PM ke beech visit kar sakte hain.";
        }

        if (containsAny(lower, "document", "documents")) {
            return "Admission ke liye generally 10th marksheet, 12th marksheet, ID proof aur passport size photos chahiye hote hain.";
        }

        if (containsAny(lower, "library")) {
            return "Library working days me 9:30 AM se 3:30 PM tak open rehti hai.";
        }

        if (containsAny(lower, "timing", "time")) {
            return "College timing Monday se Saturday, 9:00 AM se 4:00 PM tak hai.";
        }

        if (containsAny(lower, "hostel")) {
            return "Hostel facility ke liye college administration se direct contact karna better rahega.";
        }

        if (containsAny(lower, "contact", "phone", "number")) {
            return "College contact number hai: +91-9876543210";
        }

        if (containsAny(lower, "email", "mail")) {
            return "College email ID hai: info@mycollege.com";
        }

        if (containsAny(lower, "address", "location")) {
            return "College Main Market, City Center ke paas located hai.";
        }

        if (containsAny(lower, "thank", "thanks", "shukriya", "dhanyavaad")) {
            return "You're welcome! Aur kuch poochna ho to bataiye.";
        }

        return "Main aapki help kar sakta hoon. Aap BCA, BBA, fees, admission, documents ya contact details ke baare me pooch sakte hain.";
    }

    private static String[] suggestOptions(String msg) {
        String lower = msg.toLowerCase();

        if (containsAny(lower, "bca", "bba", "course")) {
            return new String[]{"fees", "admission", "documents", "contact"};
        }
        if (containsAny(lower, "fees")) {
            return new String[]{"admission", "documents", "contact"};
        }
        if (containsAny(lower, "admission")) {
            return new String[]{"documents", "fees", "contact"};
        }
        return new String[]{"BCA", "BBA", "fees", "admission", "contact"};
    }

    // =========================
    // Login / Password
    // =========================
    private static void handleLogin(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        String username = opt(getJsonValue(body, "username"), "");
        String password = opt(getJsonValue(body, "password"), "");

        String[] parts = Files.readString(new File(ADMIN_FILE).toPath(), StandardCharsets.UTF_8).trim().split(",", 2);

        if (parts.length == 2 && parts[0].equals(username) && parts[1].equals(password)) {
            sendText(ex, 200, "success");
        } else {
            sendText(ex, 401, "invalid");
        }
    }

    private static void handleChangePassword(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        String username = opt(getJsonValue(body, "username"), "");
        String oldPassword = opt(getJsonValue(body, "oldPassword"), "");
        String newPassword = opt(getJsonValue(body, "newPassword"), "");

        String[] parts = Files.readString(new File(ADMIN_FILE).toPath(), StandardCharsets.UTF_8).trim().split(",", 2);

        if (parts.length == 2 && parts[0].equals(username) && parts[1].equals(oldPassword)) {
            Files.writeString(new File(ADMIN_FILE).toPath(), username + "," + newPassword, StandardCharsets.UTF_8);
            sendText(ex, 200, "Password changed successfully.");
        } else {
            sendText(ex, 401, "Wrong old password.");
        }
    }

    // =========================
    // Inquiry save
    // =========================
    private static void handleInquiry(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);

        appendCsvRow(INQUIRIES_FILE, new String[]{
                LocalDateTime.now().toString(),
                opt(getJsonValue(body, "name"), ""),
                opt(getJsonValue(body, "email"), ""),
                opt(getJsonValue(body, "phone"), ""),
                opt(getJsonValue(body, "course"), ""),
                opt(getJsonValue(body, "message"), "")
        });

        sendText(ex, 200, "Inquiry submitted successfully.");
    }

    // =========================
    // Read APIs
    // =========================
    private static void handleGetLeads(HttpExchange ex) throws IOException {
        addCors(ex);
        sendText(ex, 200, readFile(LEADS_FILE));
    }

    private static void handleGetInquiries(HttpExchange ex) throws IOException {
        addCors(ex);
        sendText(ex, 200, readFile(INQUIRIES_FILE));
    }

    private static void handleGetChatlog(HttpExchange ex) throws IOException {
        addCors(ex);
        sendText(ex, 200, readFile(CHATLOG_FILE));
    }

    private static void handleGetDeleted(HttpExchange ex) throws IOException {
        addCors(ex);
        sendText(ex, 200, readFile(DELETED_FILE));
    }

    private static void handleCourseStats(HttpExchange ex) throws IOException {
        addCors(ex);

        int bca = 0, bba = 0;
        List<String> lines = Files.readAllLines(new File(LEADS_FILE).toPath(), StandardCharsets.UTF_8);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] row = parseCsvLine(line);
            if (row.length > 2) {
                String course = row[2].trim().toUpperCase();
                if ("BCA".equals(course)) bca++;
                if ("BBA".equals(course)) bba++;
            }
        }

        String json = "[{\"course\":\"BCA\",\"count\":" + bca + "},{\"course\":\"BBA\",\"count\":" + bba + "}]";
        sendJson(ex, 200, json);
    }

    // =========================
    // Lead edit/delete
    // =========================
    private static void handleUpdateLead(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        int rowIndex = Integer.parseInt(opt(getJsonValue(body, "rowIndex"), "-1"));
        String name = opt(getJsonValue(body, "name"), "");
        String course = opt(getJsonValue(body, "course"), "");
        String contact = opt(getJsonValue(body, "contact"), "");

        List<String> lines = Files.readAllLines(new File(LEADS_FILE).toPath(), StandardCharsets.UTF_8);

        if (rowIndex > 0 && rowIndex < lines.size()) {
            String[] old = parseCsvLine(lines.get(rowIndex));
            String dateTime = old.length > 0 ? old[0] : "";
            String status = old.length > 4 ? old[4] : "Complete";

            lines.set(rowIndex, toCsv(dateTime, name, course, contact, status));
            Files.write(new File(LEADS_FILE).toPath(), lines, StandardCharsets.UTF_8);
            sendText(ex, 200, "Lead updated successfully.");
            return;
        }

        sendText(ex, 400, "Invalid lead row.");
    }

    private static void handleDeleteLead(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        int rowIndex = Integer.parseInt(opt(getJsonValue(body, "rowIndex"), "-1"));

        List<String> lines = Files.readAllLines(new File(LEADS_FILE).toPath(), StandardCharsets.UTF_8);
        if (rowIndex > 0 && rowIndex < lines.size()) {
            String deletedRow = lines.remove(rowIndex);
            appendCsvRow(DELETED_FILE, new String[]{
                    "lead", LocalDateTime.now().toString(), String.valueOf(rowIndex), deletedRow
            });
            Files.write(new File(LEADS_FILE).toPath(), lines, StandardCharsets.UTF_8);
            sendText(ex, 200, "Lead deleted successfully.");
            return;
        }

        sendText(ex, 400, "Invalid lead row.");
    }

    // =========================
    // Inquiry edit/delete
    // =========================
    private static void handleUpdateInquiry(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        int rowIndex = Integer.parseInt(opt(getJsonValue(body, "rowIndex"), "-1"));
        String name = opt(getJsonValue(body, "name"), "");
        String email = opt(getJsonValue(body, "email"), "");
        String phone = opt(getJsonValue(body, "phone"), "");
        String course = opt(getJsonValue(body, "course"), "");
        String message = opt(getJsonValue(body, "message"), "");

        List<String> lines = Files.readAllLines(new File(INQUIRIES_FILE).toPath(), StandardCharsets.UTF_8);

        if (rowIndex > 0 && rowIndex < lines.size()) {
            String[] old = parseCsvLine(lines.get(rowIndex));
            String dateTime = old.length > 0 ? old[0] : "";

            lines.set(rowIndex, toCsv(dateTime, name, email, phone, course, message));
            Files.write(new File(INQUIRIES_FILE).toPath(), lines, StandardCharsets.UTF_8);
            sendText(ex, 200, "Inquiry updated successfully.");
            return;
        }

        sendText(ex, 400, "Invalid inquiry row.");
    }

    private static void handleDeleteInquiry(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        int rowIndex = Integer.parseInt(opt(getJsonValue(body, "rowIndex"), "-1"));

        List<String> lines = Files.readAllLines(new File(INQUIRIES_FILE).toPath(), StandardCharsets.UTF_8);
        if (rowIndex > 0 && rowIndex < lines.size()) {
            String deletedRow = lines.remove(rowIndex);
            appendCsvRow(DELETED_FILE, new String[]{
                    "inquiry", LocalDateTime.now().toString(), String.valueOf(rowIndex), deletedRow
            });
            Files.write(new File(INQUIRIES_FILE).toPath(), lines, StandardCharsets.UTF_8);
            sendText(ex, 200, "Inquiry deleted successfully.");
            return;
        }

        sendText(ex, 400, "Invalid inquiry row.");
    }

    // =========================
    // Deleted restore / permanent delete
    // =========================
    private static void handleRestoreDeleted(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        String rowIndexesText = opt(getJsonValue(body, "rowIndexes"), "");

        if (rowIndexesText.isEmpty()) {
            sendText(ex, 400, "No rows selected.");
            return;
        }

        List<Integer> rowIndexes = parseRowIndexes(rowIndexesText);
        List<String> deletedLines = Files.readAllLines(new File(DELETED_FILE).toPath(), StandardCharsets.UTF_8);

        if (deletedLines.size() <= 1) {
            sendText(ex, 200, "No deleted records found.");
            return;
        }

        Set<Integer> rowSet = new HashSet<>(rowIndexes);
        List<String> remaining = new ArrayList<>();
        remaining.add(deletedLines.get(0));

        for (int i = 1; i < deletedLines.size(); i++) {
            String line = deletedLines.get(i);

            if (rowSet.contains(i)) {
                String[] row = parseCsvLine(line);
                if (row.length >= 4) {
                    String type = row[0];
                    String originalData = row[3];

                    if ("lead".equalsIgnoreCase(type)) {
                        appendRawLine(LEADS_FILE, originalData);
                    } else if ("inquiry".equalsIgnoreCase(type)) {
                        appendRawLine(INQUIRIES_FILE, originalData);
                    }
                }
            } else {
                remaining.add(line);
            }
        }

        Files.write(new File(DELETED_FILE).toPath(), remaining, StandardCharsets.UTF_8);
        sendText(ex, 200, "Selected deleted records restored successfully.");
    }

    private static void handleDeleteDeletedPermanent(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handleOptions(ex)) return;

        String body = readBody(ex);
        String rowIndexesText = opt(getJsonValue(body, "rowIndexes"), "");

        if (rowIndexesText.isEmpty()) {
            sendText(ex, 400, "No rows selected.");
            return;
        }

        List<Integer> rowIndexes = parseRowIndexes(rowIndexesText);
        List<String> deletedLines = Files.readAllLines(new File(DELETED_FILE).toPath(), StandardCharsets.UTF_8);

        if (deletedLines.size() <= 1) {
            sendText(ex, 200, "No deleted records found.");
            return;
        }

        Set<Integer> rowSet = new HashSet<>(rowIndexes);
        List<String> remaining = new ArrayList<>();
        remaining.add(deletedLines.get(0));

        for (int i = 1; i < deletedLines.size(); i++) {
            if (!rowSet.contains(i)) {
                remaining.add(deletedLines.get(i));
            }
        }

        Files.write(new File(DELETED_FILE).toPath(), remaining, StandardCharsets.UTF_8);
        sendText(ex, 200, "Selected deleted records permanently removed.");
    }

    // =========================
    // File helpers
    // =========================
    private static void appendChat(String clientId, String userMsg, String botReply) {
        try {
            String block =
                    "DateTime: " + LocalDateTime.now() + System.lineSeparator() +
                    "ClientId: " + clientId + System.lineSeparator() +
                    "User: " + userMsg + System.lineSeparator() +
                    "Bot: " + botReply + System.lineSeparator() +
                    "----------------------------------------" + System.lineSeparator();
            appendRawText(CHATLOG_FILE, block);
        } catch (Exception ignored) {
        }
    }

    private static void appendCsvRow(String file, String[] values) throws IOException {
        appendRawLine(file, toCsv(values));
    }

    private static void appendRawLine(String file, String line) throws IOException {
        appendRawText(file, line + System.lineSeparator());
    }

    private static void appendRawText(String file, String text) throws IOException {
        Files.writeString(new File(file).toPath(), text, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String readFile(String file) throws IOException {
        File f = new File(file);
        if (!f.exists()) return "";
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    // =========================
    // CSV helpers
    // =========================
    private static String toCsv(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append("\"").append(values[i] == null ? "" : values[i].replace("\"", "\"\"")).append("\"");
            if (i < values.length - 1) sb.append(",");
        }
        return sb.toString();
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        out.add(current.toString());
        return out.toArray(new String[0]);
    }

    private static List<Integer> parseRowIndexes(String rowIndexesText) {
        List<Integer> list = new ArrayList<>();
        if (rowIndexesText == null || rowIndexesText.isBlank()) return list;

        String[] parts = rowIndexesText.split(",");
        for (String p : parts) {
            try {
                list.add(Integer.parseInt(p.trim()));
            } catch (Exception ignored) {
            }
        }

        list.sort(Collections.reverseOrder());
        return list;
    }

    // =========================
    // JSON helpers
    // =========================
    private static String getJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|\\d+)");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            String quoted = matcher.group(2);
            if (quoted != null) {
                return quoted.replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            return matcher.group(1);
        }
        return null;
    }

    private static String buildReplyJson(String reply, String[] options) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"reply\":\"").append(escapeJson(reply)).append("\",\"options\":[");
        for (int i = 0; i < options.length; i++) {
            sb.append("\"").append(escapeJson(options[i])).append("\"");
            if (i < options.length - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // =========================
    // Common helpers
    // =========================
    private static boolean handleOptions(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void addCors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        sendText(ex, status, json);
    }

    private static boolean containsAny(String msg, String... words) {
        for (String w : words) {
            if (msg.contains(w.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean looksLikeName(String text) {
        return text.matches("^[A-Za-z ]{2,40}$");
    }

    private static boolean looksLikePhone(String text) {
        return text.matches("^[0-9+\\- ]{8,15}$");
    }

    private static boolean looksLikeEmail(String text) {
        return text.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    private static String detectCourse(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("bca")) return "BCA";
        if (lower.contains("bba")) return "BBA";
        return null;
    }

    private static String opt(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String capitalize(String text) {
        String[] parts = text.trim().toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private static String pick(String... options) {
        if (options == null || options.length == 0) return "";
        int index = (int) (Math.random() * options.length);
        return options[index];
    }

    private static class UserSession {
        String name;
        String course;
        String contact;
        String status = "Complete";
    }

    private static class BotResponse {
        String reply;
        String[] options;

        BotResponse(String reply, String[] options) {
            this.reply = reply;
            this.options = options;
        }
    }
}
