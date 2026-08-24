import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class FixAugustAttendanceList {
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");
    private static final YearMonth MONTH = YearMonth.of(2026, 8);
    private static final LocalDate END = LocalDate.of(2026, 8, 22);

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
        if (appData == null) appData = new Main.AppData(staff, new java.util.TreeSet<>());
        appData.publicHolidays.add("2026-08-15");

        apply(staff.get("OP002"), Set.of(19), Set.of(17, 18, 21)); // Shyam lal
        apply(staff.get("OP004"), Set.of(), Set.of(11, 12, 13));    // Rahul Yadav
        apply(staff.get("OP005"), Set.of(), Set.of());             // Anshu
        apply(staff.get("OP006"), Set.of(3), Set.of(1, 17));        // Neha
        apply(staff.get("OP007"), Set.of(), Set.of());             // Annu
        apply(staff.get("OP008"), Set.of(), Set.of(12));           // Preeti

        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(appData);
        }
        print(staff.get("OP002"));
        print(staff.get("OP004"));
        print(staff.get("OP005"));
        print(staff.get("OP006"));
        print(staff.get("OP007"));
        print(staff.get("OP008"));
    }

    private static void apply(Main.Staff employee, Set<Integer> halfDays, Set<Integer> absentDays) {
        if (employee == null) return;
        employee.attendance.entrySet().removeIf(entry -> YearMonth.from(LocalDate.parse(entry.getKey())).equals(MONTH));

        int full = 0;
        int half = 0;
        int absent = 0;
        LocalDate cursor = MONTH.atDay(1);
        while (!cursor.isAfter(END)) {
            if (cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                int day = cursor.getDayOfMonth();
                String date = cursor.toString();
                if (halfDays.contains(day)) {
                    employee.attendance.put(date, new Main.Attendance("Half day", 0.5, employee.dailyWage));
                    half++;
                } else if (absentDays.contains(day)) {
                    employee.attendance.put(date, new Main.Attendance("Absent", 0, employee.dailyWage));
                    absent++;
                } else if (date.equals("2026-08-15")) {
                    employee.attendance.put(date, new Main.Attendance("Paid holiday", 1.0, employee.dailyWage));
                    full++;
                } else {
                    employee.attendance.put(date, new Main.Attendance("Full day", 1.0, employee.dailyWage));
                    full++;
                }
            }
            cursor = cursor.plusDays(1);
        }

        employee.summaryMonth = MONTH.toString();
        employee.summaryFullDays = full;
        employee.summaryHalfDays = half;
        employee.summaryAbsentDays = absent;
        employee.summaryAppliedDates.clear();
        employee.summaryAppliedDates.addAll(employee.attendance.keySet());
    }

    private static void print(Main.Staff employee) {
        if (employee == null) return;
        double payable = employee.summaryFullDays + employee.summaryHalfDays * 0.5;
        System.out.println(employee.name + ": full=" + employee.summaryFullDays + ", half=" + employee.summaryHalfDays
                + ", absent=" + employee.summaryAbsentDays + ", payableDays=" + payable);
    }
}
