import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

public class InspectAugustRows {
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Main.Staff> staff;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(DB_FILE))) {
            Object loaded = input.readObject();
            staff = loaded instanceof Main.AppData ? ((Main.AppData) loaded).staff : (Map<String, Main.Staff>) loaded;
        }
        for (String id : new String[]{"OP008", "OP007", "OP006", "OP005", "OP004", "OP003", "OP002"}) {
            Main.Staff employee = staff.get(id);
            if (employee == null) continue;
            System.out.println(employee.name);
            for (int day = 24; day <= 29; day++) {
                String date = LocalDate.of(2026, 8, day).toString();
                Main.Attendance row = employee.attendance.get(date);
                System.out.println("  " + date + " = " + (row == null ? "Not marked" : row.status + " (" + row.dayValue + ")"));
            }
        }
    }
}
