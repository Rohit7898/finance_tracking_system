import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

public class InspectDataUntil {
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Main.Staff> staff;
        Main.AppData appData = null;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(DB_FILE))) {
            Object loaded = input.readObject();
            if (loaded instanceof Main.AppData) {
                appData = (Main.AppData) loaded;
                staff = appData.staff;
            } else {
                staff = (Map<String, Main.Staff>) loaded;
            }
        }

        LocalDate latestAttendance = null;
        LocalDate latestSalary = null;
        LocalDate latestAdvance = null;
        LocalDate latestLeave = null;

        for (Main.Staff employee : staff.values()) {
            for (String date : employee.attendance.keySet()) {
                latestAttendance = max(latestAttendance, parse(date));
            }
            for (Main.SalaryPayment payment : employee.salaryPayments) {
                latestSalary = max(latestSalary, parse(payment.date));
            }
            for (Main.AdvanceTransaction transaction : employee.advanceTransactions) {
                latestAdvance = max(latestAdvance, parse(transaction.date));
            }
            for (Main.Leave leave : employee.leaves) {
                latestLeave = max(latestLeave, parse(leave.to));
            }
        }

        System.out.println("Latest attendance date: " + text(latestAttendance));
        System.out.println("Latest salary paid date: " + text(latestSalary));
        System.out.println("Latest advance ledger date: " + text(latestAdvance));
        System.out.println("Latest leave date: " + text(latestLeave));
        System.out.println("Public holidays: " + (appData == null ? "[]" : appData.publicHolidays));
        System.out.println();

        for (Main.Staff employee : staff.values()) {
            if (!"employee".equals(employee.role)) continue;
            System.out.println(employee.name
                    + " | attendanceUntil=" + text(employee.attendance.keySet().stream().map(InspectDataUntil::parse).max(LocalDate::compareTo).orElse(null))
                    + " | summaryMonth=" + employee.summaryMonth
                    + " | F/H/A=" + employee.summaryFullDays + "/" + employee.summaryHalfDays + "/" + employee.summaryAbsentDays
                    + " | lastPaid=" + blank(employee.lastSalaryDate)
                    + " | advance=" + employee.advanceBalance);
        }
    }

    private static LocalDate parse(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private static LocalDate max(LocalDate current, LocalDate next) {
        if (next == null) return current;
        if (current == null || next.isAfter(current)) return next;
        return current;
    }

    private static String text(LocalDate date) {
        return date == null ? "none" : date.toString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
