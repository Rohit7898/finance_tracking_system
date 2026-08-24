import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public class CleanupTodaySalaryRows {
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
        String today = LocalDate.now().toString();
        int removed = 0;
        for (Main.Staff employee : staff.values()) {
            int before = employee.salaryPayments.size();
            employee.salaryPayments.removeIf(payment -> today.equals(payment.date));
            removed += before - employee.salaryPayments.size();
            Main.SalaryPayment latest = employee.salaryPayments.stream()
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (latest != null) {
                employee.lastSalaryDate = latest.date;
                employee.lastSalaryAmount = latest.amount;
            }
        }
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(appData == null ? new LinkedHashMap<>(staff) : new Main.AppData(staff, appData.publicHolidays));
        }
        System.out.println("Removed " + removed + " salary payment rows dated " + today);
    }
}
