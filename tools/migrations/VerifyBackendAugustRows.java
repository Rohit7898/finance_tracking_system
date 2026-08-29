import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;

public class VerifyBackendAugustRows {
    private static final String BASE_URL = "http://192.168.29.23:8080";

    public static void main(String[] args) throws Exception {
        String json = get(BASE_URL + "/api/state");
        System.out.println("publicHolidays=" + arrayText(json, "publicHolidays"));
        for (String id : new String[]{"OP008", "OP007", "OP006", "OP005", "OP004", "OP003", "OP002"}) {
            String object = staffObject(json, id);
            System.out.println(jsonString(object, "name"));
            for (int day = 24; day <= 29; day++) {
                String date = LocalDate.of(2026, 8, day).toString();
                System.out.println("  " + date + " = " + attendanceStatus(object, date));
            }
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static String staffObject(String json, String staffId) {
        String marker = "\"id\":\"" + staffId + "\"";
        int markerIndex = json.indexOf(marker);
        int start = json.lastIndexOf("{", markerIndex);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return "";
    }

    private static String attendanceStatus(String staffJson, String date) {
        String marker = "\"date\":\"" + date + "\"";
        int index = staffJson.indexOf(marker);
        if (index < 0) return "Not marked";
        int start = staffJson.lastIndexOf("{", index);
        int end = staffJson.indexOf("}", index);
        if (start < 0 || end < 0) return "Not marked";
        String row = staffJson.substring(start, end + 1);
        return jsonString(row, "status") + " (" + jsonNumber(row, "dayValue") + ")";
    }

    private static String jsonString(String object, String key) {
        String marker = "\"" + key + "\":\"";
        int start = object.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = object.indexOf("\"", start);
        return end < 0 ? "" : object.substring(start, end);
    }

    private static String jsonNumber(String object, String key) {
        String marker = "\"" + key + "\":";
        int start = object.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = start;
        while (end < object.length() && "0123456789.-".indexOf(object.charAt(end)) >= 0) end++;
        return object.substring(start, end);
    }

    private static String arrayText(String json, String key) {
        String marker = "\"" + key + "\":[";
        int start = json.indexOf(marker);
        if (start < 0) return "[]";
        start += marker.length() - 1;
        int end = json.indexOf("]", start);
        return end < 0 ? "[]" : json.substring(start, end + 1);
    }
}
