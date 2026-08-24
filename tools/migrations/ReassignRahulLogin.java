import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReassignRahulLogin {
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Main.AppData appData = null;
        Map<String, Main.Staff> staff;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(DB_FILE))) {
            Object loaded = input.readObject();
            if (loaded instanceof Main.AppData data) {
                appData = data;
                staff = data.staff;
            } else {
                staff = (Map<String, Main.Staff>) loaded;
            }
        }
        staff.remove("OP001");
        Main.Staff rahul = staff.get("OP003");
        if (rahul != null) rahul.phone = "8962569527";
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(appData == null ? new LinkedHashMap<>(staff) : new Main.AppData(staff, appData.publicHolidays));
        }
        System.out.println("Removed OP001 and assigned 8962569527 to OP003 Rahul Joshi");
    }
}
