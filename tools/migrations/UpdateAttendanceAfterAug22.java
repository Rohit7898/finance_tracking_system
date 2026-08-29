import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Set;

public class UpdateAttendanceAfterAug22 {
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");
    private static final YearMonth MONTH = YearMonth.of(2026, 8);
    private static final LocalDate START = LocalDate.of(2026, 8, 24);
    private static final LocalDate END = LocalDate.of(2026, 8, 29);
    private static final String PUBLIC_HOLIDAY = "2026-08-28";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Main.AppData appData = null;
        Map<String, Main.Staff> staff;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(DB_FILE))) {
            Object loaded = input.readObject();
            if (loaded instanceof Main.AppData) {
                appData = (Main.AppData) loaded;
                staff = appData.staff;
            } else {
                staff = (Map<String, Main.Staff>) loaded;
            }
        }
        if (appData == null) appData = new Main.AppData(staff, new java.util.TreeSet<>());
        appData.publicHolidays.add(PUBLIC_HOLIDAY);

        apply(staff.get("OP008"), Set.of());             // Preeti: all 6 working/payable days
        apply(staff.get("OP002"), Set.of(27));           // Shyam lal: absent 27, present 24/25/26/29, paid holiday 28
        apply(staff.get("OP003"), Set.of());             // Rahul Joshi: all 6 working/payable days
        apply(staff.get("OP005"), Set.of());             // Anshu: all 6 working/payable days
        apply(staff.get("OP006"), Set.of());             // Neha: all 6 working/payable days
        apply(staff.get("OP007"), Set.of());             // Annu: all 6 working/payable days
        apply(staff.get("OP004"), Set.of(24, 25, 26));   // Rahul Yadav: absent 24/25/26, present 27/29, paid holiday 28

        recalculateSummary(staff);

        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(appData);
        }

        print(staff.get("OP008"));
        print(staff.get("OP002"));
        print(staff.get("OP003"));
        print(staff.get("OP005"));
        print(staff.get("OP006"));
        print(staff.get("OP007"));
        print(staff.get("OP004"));
    }

    private static void apply(Main.Staff employee, Set<Integer> absentDays) {
        if (employee == null) return;
        LocalDate cursor = START;
        while (!cursor.isAfter(END)) {
            if (cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                String date = cursor.toString();
                int day = cursor.getDayOfMonth();
                if (absentDays.contains(day)) {
                    employee.attendance.put(date, new Main.Attendance("Absent", 0, employee.dailyWage));
                } else if (PUBLIC_HOLIDAY.equals(date)) {
                    employee.attendance.put(date, new Main.Attendance("Paid holiday", 1.0, employee.dailyWage));
                } else {
                    employee.attendance.put(date, new Main.Attendance("Full day", 1.0, employee.dailyWage));
                }
            }
            cursor = cursor.plusDays(1);
        }
    }

    private static void recalculateSummary(Map<String, Main.Staff> staff) {
        for (Main.Staff employee : staff.values()) {
            if (!"employee".equals(employee.role)) continue;
            int full = 0;
            int half = 0;
            int absent = 0;
            for (Map.Entry<String, Main.Attendance> entry : employee.attendance.entrySet()) {
                LocalDate date = LocalDate.parse(entry.getKey());
                if (!YearMonth.from(date).equals(MONTH) || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                String status = entry.getValue().status;
                if ("Full day".equals(status) || "Paid holiday".equals(status)) full++;
                if ("Half day".equals(status)) half++;
                if ("Absent".equals(status)) absent++;
            }
            employee.summaryMonth = MONTH.toString();
            employee.summaryFullDays = full;
            employee.summaryHalfDays = half;
            employee.summaryAbsentDays = absent;
            employee.summaryAppliedDates.clear();
            employee.summaryAppliedDates.addAll(employee.attendance.keySet());
        }
    }

    private static void print(Main.Staff employee) {
        if (employee == null) return;
        double payable = employee.summaryFullDays + employee.summaryHalfDays * 0.5;
        System.out.println(employee.name + ": F/H/A=" + employee.summaryFullDays + "/"
                + employee.summaryHalfDays + "/" + employee.summaryAbsentDays + ", payableDays=" + payable);
    }
}
