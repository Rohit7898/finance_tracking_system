import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

public class FixRahulAugustAttendance {
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

        if (appData == null) {
            appData = new Main.AppData(staff, new java.util.TreeSet<>());
        }
        appData.publicHolidays.add("2026-08-15");

        Main.Staff rahul = staff.get("OP003");
        if (rahul == null) throw new IllegalStateException("OP003 Rahul Joshi not found");

        YearMonth month = YearMonth.of(2026, 8);
        rahul.attendance.entrySet().removeIf(entry -> YearMonth.from(LocalDate.parse(entry.getKey())).equals(month));

        int fullDays = 0;
        int halfDays = 0;
        int absentDays = 0;
        LocalDate cursor = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 22);
        while (!cursor.isAfter(end)) {
            if (cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                String date = cursor.toString();
                if (date.equals("2026-08-03")) {
                    rahul.attendance.put(date, new Main.Attendance("Half day", 0.5, rahul.dailyWage));
                    halfDays++;
                } else if (date.equals("2026-08-17") || date.equals("2026-08-18")) {
                    rahul.attendance.put(date, new Main.Attendance("Absent", 0, rahul.dailyWage));
                    absentDays++;
                } else if (date.equals("2026-08-15")) {
                    rahul.attendance.put(date, new Main.Attendance("Paid holiday", 1.0, rahul.dailyWage));
                    fullDays++;
                } else {
                    rahul.attendance.put(date, new Main.Attendance("Full day", 1.0, rahul.dailyWage));
                    fullDays++;
                }
            }
            cursor = cursor.plusDays(1);
        }

        rahul.summaryMonth = month.toString();
        rahul.summaryFullDays = fullDays;
        rahul.summaryHalfDays = halfDays;
        rahul.summaryAbsentDays = absentDays;
        rahul.summaryAppliedDates.clear();
        rahul.summaryAppliedDates.addAll(rahul.attendance.keySet());

        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(appData);
        }
        System.out.println("Rahul August fixed: full=" + fullDays + ", half=" + halfDays + ", absent=" + absentDays
                + ", payableDays=" + (fullDays + halfDays * 0.5));
    }
}
