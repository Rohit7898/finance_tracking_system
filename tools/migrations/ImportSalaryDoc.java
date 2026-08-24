import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

public class ImportSalaryDoc {
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

        apply(staff.get("OP002"), 490, 16, 1, 3, 3185, "2026-08-19", 24770);
        apply(staff.get("OP003"), 440, 17, 1, 2, 2020, "2026-08-08", 8560);
        apply(staff.get("OP004"), 520, 16, 0, 3, 1050, "2026-08-14", 2490);
        apply(staff.get("OP005"), 270, 19, 0, 0, 7290, "2026-07-31", 25000);
        apply(staff.get("OP006"), 300, 17, 1, 2, 8000, "2026-07-31", 0);
        apply(staff.get("OP007"), 310, 19, 0, 0, 7440, "2026-07-31", 540);
        apply(staff.get("OP008"), 580, 18, 0, 1, 8700, "2026-08-19", 22270);

        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(appData == null ? new LinkedHashMap<>(staff) : new Main.AppData(staff, appData.publicHolidays));
        }
        System.out.println("Imported salary document fields into " + DB_FILE.toAbsolutePath());
    }

    private static void apply(Main.Staff employee, double dailyWage, int fullDays, int halfDays, int absentDays,
                              double salaryPaid, String salaryDate, double advanceBalance) {
        if (employee == null) return;
        employee.dailyWage = dailyWage;
        employee.lastSalaryAmount = salaryPaid;
        employee.lastSalaryDate = salaryDate;
        employee.advanceBalance = advanceBalance;
        YearMonth month = YearMonth.now();
        employee.summaryMonth = month.toString();
        employee.summaryFullDays = fullDays;
        employee.summaryHalfDays = halfDays;
        employee.summaryAbsentDays = absentDays;
        employee.summaryAppliedDates.clear();

        boolean exists = employee.salaryPayments.stream()
                .anyMatch(payment -> payment.date.equals(salaryDate) && Math.abs(payment.amount - salaryPaid) < 0.01);
        if (!exists) employee.salaryPayments.add(new Main.SalaryPayment(salaryDate, salaryPaid));

        employee.attendance.entrySet().removeIf(entry -> YearMonth.from(LocalDate.parse(entry.getKey())).equals(month));
    }
}
