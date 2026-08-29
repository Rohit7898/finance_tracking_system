import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public class PushAug24To29ToBackend {
    private static final String BASE_URL = "http://192.168.29.23:8080";

    public static void main(String[] args) throws Exception {
        post("/api/holiday", "{\"date\":\"2026-08-29\",\"action\":\"remove\"}");
        post("/api/holiday", "{\"date\":\"2026-08-28\"}");

        Map<String, Map<String, String>> updates = new LinkedHashMap<>();
        updates.put("OP008", days("Full day", "Full day", "Full day", "Full day", "Paid holiday", "Full day"));
        updates.put("OP007", days("Full day", "Full day", "Full day", "Full day", "Paid holiday", "Full day"));
        updates.put("OP006", days("Full day", "Full day", "Full day", "Full day", "Paid holiday", "Full day"));
        updates.put("OP005", days("Full day", "Full day", "Full day", "Full day", "Paid holiday", "Full day"));
        updates.put("OP004", days("Absent", "Absent", "Absent", "Full day", "Paid holiday", "Full day"));
        updates.put("OP003", days("Full day", "Full day", "Full day", "Full day", "Paid holiday", "Full day"));
        updates.put("OP002", days("Full day", "Full day", "Full day", "Absent", "Paid holiday", "Full day"));

        for (Map.Entry<String, Map<String, String>> staff : updates.entrySet()) {
            for (Map.Entry<String, String> row : staff.getValue().entrySet()) {
                post("/api/attendance", "{\"staffId\":\"" + staff.getKey()
                        + "\",\"date\":\"" + row.getKey()
                        + "\",\"status\":\"" + row.getValue()
                        + "\",\"adminOverride\":\"true\"}");
            }
        }

        System.out.println("Pushed corrected 2026-08-24 to 2026-08-29 rows to " + BASE_URL);
    }

    private static Map<String, String> days(String day24, String day25, String day26, String day27, String day28, String day29) {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(LocalDate.of(2026, 8, 24).toString(), day24);
        rows.put(LocalDate.of(2026, 8, 25).toString(), day25);
        rows.put(LocalDate.of(2026, 8, 26).toString(), day26);
        rows.put(LocalDate.of(2026, 8, 27).toString(), day27);
        rows.put(LocalDate.of(2026, 8, 28).toString(), day28);
        rows.put(LocalDate.of(2026, 8, 29).toString(), day29);
        return rows;
    }

    private static void post(String path, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream != null) stream.close();
        connection.disconnect();
        if (code >= 400) throw new RuntimeException(path + " failed with HTTP " + code);
    }
}
