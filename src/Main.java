import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Main {
    private static final int PORT = envPort();
    private static final double DEFAULT_DAILY_WAGE = 650;
    private static final Path DB_FILE = Paths.get("data", "company-attendance.db");
    private static final String DATABASE_URL = System.getenv("DATABASE_URL");
    private final Map<String, Staff> staff = new LinkedHashMap<>();
    private final TreeSet<String> publicHolidays = new TreeSet<>();

    public static void main(String[] args) throws IOException {
        System.setProperty("java.net.preferIPv4Stack", "true");
        new Main().start();
    }

    private static int envPort() {
        try {
            return Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        } catch (Exception exception) {
            return 8080;
        }
    }

    private void start() throws IOException {
        loadOrSeed();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", this::home);
        server.createContext("/api/state", this::state);
        server.createContext("/api/attendance", this::attendance);
        server.createContext("/api/leave", this::leave);
        server.createContext("/api/leave-status", this::leaveStatus);
        server.createContext("/api/photo", this::photo);
        server.createContext("/api/advance", this::advance);
        server.createContext("/api/repayment", this::repayment);
        server.createContext("/api/salary-payment", this::salaryPayment);
        server.createContext("/api/daily-wage", this::dailyWage);
        server.createContext("/api/staff", this::staffUpdate);
        server.createContext("/api/holiday", this::holiday);
        server.createContext("/api/month-attendance", this::monthAttendance);
        server.createContext("/api/login", this::login);
        server.createContext("/api/admin-login", this::adminLogin);
        server.createContext("/api/admin-logout", this::adminLogout);
        server.setExecutor(null);
        server.start();
        System.out.println("Shared admin backend running: http://localhost:" + PORT);
        System.out.println(usingPostgres() ? "Persistent DB: Postgres DATABASE_URL" : "Persistent DB file: " + DB_FILE.toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private void loadOrSeed() {
        if (usingPostgres()) {
            try {
                initPostgres();
                loadPostgres();
                boolean changed = false;
                if (staff.isEmpty()) {
                    seed();
                    changed = true;
                }
                changed |= ensureDefaultStaff();
                changed |= ensureAttendanceWages();
                changed |= ensureLedgerDefaults();
                if (changed) save();
                return;
            } catch (Exception exception) {
                System.out.println("Could not load Postgres DB, falling back to file DB: " + exception.getMessage());
            }
        }
        boolean changed = false;
        if (Files.exists(DB_FILE)) {
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(DB_FILE))) {
                staff.clear();
                Object loaded = input.readObject();
                if (loaded instanceof AppData) {
                    AppData data = (AppData) loaded;
                    staff.putAll(data.staff);
                    publicHolidays.clear();
                    publicHolidays.addAll(data.publicHolidays);
                } else {
                    staff.putAll((Map<String, Staff>) loaded);
                }
                changed = ensureDefaultStaff();
                changed |= ensureAttendanceWages();
                changed |= ensureLedgerDefaults();
                if (changed) save();
                return;
            } catch (Exception exception) {
                System.out.println("Could not load DB, starting seed data: " + exception.getMessage());
            }
        }
        seed();
        ensureDefaultStaff();
        save();
    }

    private void seed() {
        Staff rohit = new Staff("OP001", "Rohit Prajapati", "Rohit", "8962569527", 650);
        rohit.dob = "1994-12-01";
        rohit.joiningDate = "2026-06-01";
        rohit.emergencyContact = "Not added yet";
        rohit.lastSalaryAmount = 3900;
        rohit.lastSalaryDate = LocalDate.now().minusDays(6).toString();
        rohit.salaryPayments.add(new SalaryPayment(rohit.lastSalaryDate, 3900));
        staff.put(rohit.id, rohit);

        Staff bhumika = new Staff("ADM001", "Bhumika Kumawat", "Bhumika", "8962569528", 0);
        bhumika.role = "admin";
        bhumika.loginPhones.add("8962569527");
        bhumika.dob = "1995-01-01";
        bhumika.joiningDate = "2026-06-01";
        bhumika.emergencyContact = "Not added yet";
        staff.put(bhumika.id, bhumika);
        LocalDate today = LocalDate.now();
        for (int i = 1; i <= 10; i++) {
            LocalDate day = today.minusDays(i);
            if (day.getDayOfWeek().getValue() != 7) {
                rohit.attendance.put(day.toString(), new Attendance("Full day", 1.0, rohit.dailyWage));
            }
        }
    }

    private boolean ensureDefaultStaff() {
        boolean changed = false;
        changed |= addStaffIfMissing("OP002", "Shyam lal nishad", "Shyam", "", 650);
        changed |= addStaffIfMissing("OP003", "Rahul Joshi", "Rahul", "", 650);
        changed |= addStaffIfMissing("OP004", "Rahul Yadav", "Rahul", "", 650);
        changed |= addStaffIfMissing("OP005", "Anshu", "Anshu", "", 650);
        changed |= addStaffIfMissing("OP006", "Neha Sarang", "Neha", "", 650);
        changed |= addStaffIfMissing("OP007", "Annu", "Annu", "", 650);
        changed |= addStaffIfMissing("OP008", "Preeti Sahu", "Preeti", "", 650);
        return changed;
    }

    private boolean ensureAttendanceWages() {
        boolean changed = false;
        for (Staff employee : staff.values()) {
            for (Attendance attendance : employee.attendance.values()) {
                if (attendance.earnedWage <= 0) {
                    attendance.earnedWage = DEFAULT_DAILY_WAGE;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean ensureLedgerDefaults() {
        boolean changed = false;
        for (Staff employee : staff.values()) {
            if (employee.loginPhones == null) {
                employee.loginPhones = new ArrayList<>();
                changed = true;
            }
            if (employee.advanceTransactions == null) {
                employee.advanceTransactions = new ArrayList<>();
                changed = true;
            }
            if (employee.advanceTransactions.isEmpty() && employee.advanceBalance > 0) {
                employee.advanceTransactions.add(new AdvanceTransaction(LocalDate.now().toString(), "ADVANCE", employee.advanceBalance, employee.advanceBalance));
                changed = true;
            }
            if (employee.salaryPayments == null) {
                employee.salaryPayments = new ArrayList<>();
                changed = true;
            }
            if (employee.leaves == null) {
                employee.leaves = new ArrayList<>();
                changed = true;
            }
            if (employee.summaryAppliedDates == null) {
                employee.summaryAppliedDates = new TreeSet<>();
                changed = true;
            }
        }
        Staff rohit = staff.get("OP001");
        if (rohit == null) {
            rohit = new Staff("OP001", "Rohit Prajapati", "Rohit", "8962569527", 0);
            rohit.dob = "1994-12-01";
            rohit.joiningDate = "2026-06-01";
            rohit.emergencyContact = "Not added yet";
            staff.put(rohit.id, rohit);
            changed = true;
        }
        if (!"admin".equals(rohit.role)) {
            rohit.role = "admin";
            changed = true;
        }
        if (!"8962569527".equals(last10(rohit.phone))) {
            rohit.phone = "8962569527";
            changed = true;
        }
        if (rohit.loginPhones.stream().noneMatch(phone -> last10(phone).equals("8962569527"))) {
            rohit.loginPhones.add("8962569527");
            changed = true;
        }
        Staff admin = staff.get("ADM001");
        if (admin != null && admin.loginPhones.removeIf(phone -> last10(phone).equals("8962569527"))) {
            changed = true;
        }
        for (Staff employee : staff.values()) {
            if ("OP001".equals(employee.id) || "ADM001".equals(employee.id)) continue;
            if ("8962569527".equals(last10(employee.phone))) {
                employee.phone = "";
                changed = true;
            }
            if (employee.loginPhones.removeIf(phone -> last10(phone).equals("8962569527"))) {
                changed = true;
            }
        }
        return changed;
    }

    private boolean addStaffIfMissing(String id, String name, String nickname, String phone, double dailyWage) {
        if (staff.containsKey(id)) return false;
        Staff employee = new Staff(id, name, nickname, phone, dailyWage);
        employee.dob = "1995-01-01";
        employee.joiningDate = "2026-06-01";
        employee.emergencyContact = "Not added yet";
        staff.put(employee.id, employee);
        return true;
    }

    private void save() {
        if (usingPostgres()) {
            try {
                savePostgres();
                return;
            } catch (Exception exception) {
                System.out.println("Could not save Postgres DB: " + exception.getMessage());
            }
        }
        try {
            Files.createDirectories(DB_FILE.getParent());
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
                output.writeObject(new AppData(staff, publicHolidays));
            }
        } catch (IOException exception) {
            System.out.println("Could not save DB: " + exception.getMessage());
        }
    }

    private boolean usingPostgres() {
        return !isBlank(DATABASE_URL);
    }

    private Connection db() throws Exception {
        String url = DATABASE_URL;
        String user = System.getenv("PGUSER");
        String password = System.getenv("PGPASSWORD");
        if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            url = "jdbc:" + url;
        }
        if (!isBlank(user)) {
            return DriverManager.getConnection(url, user, password == null ? "" : password);
        }
        return DriverManager.getConnection(url);
    }

    private void initPostgres() throws Exception {
        try (Connection connection = db(); Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists staff ("
                    + "id text primary key,"
                    + "role text not null default 'employee',"
                    + "full_name text not null,"
                    + "nickname text,"
                    + "phone text,"
                    + "dob date,"
                    + "joining_date date,"
                    + "emergency_contact text,"
                    + "login_aliases text,"
                    + "daily_wage numeric(10,2) not null default 0,"
                    + "advance_balance numeric(10,2) not null default 0,"
                    + "last_salary_amount numeric(10,2) not null default 0,"
                    + "last_salary_date date,"
                    + "last_repayment_date date,"
                    + "photo_base64 text"
                    + ")");
            try {
                statement.execute("alter table staff add column if not exists login_aliases text");
            } catch (Exception ignored) {
            }
            statement.execute("create table if not exists attendance ("
                    + "staff_id text not null references staff(id) on delete cascade,"
                    + "attendance_date date not null,"
                    + "status text not null,"
                    + "day_value numeric(4,2) not null,"
                    + "earned_wage numeric(10,2) not null,"
                    + "source text not null default 'employee',"
                    + "primary key (staff_id, attendance_date)"
                    + ")");
            statement.execute("create table if not exists leaves ("
                    + "id bigserial primary key,"
                    + "staff_id text not null references staff(id) on delete cascade,"
                    + "from_date date not null,"
                    + "to_date date not null,"
                    + "reason text,"
                    + "status text not null default 'Pending',"
                    + "decided_by text,"
                    + "decided_at timestamptz"
                    + ")");
            statement.execute("create table if not exists salary_payments ("
                    + "id bigserial primary key,"
                    + "staff_id text not null references staff(id) on delete cascade,"
                    + "paid_date date not null,"
                    + "amount numeric(10,2) not null,"
                    + "month_key text"
                    + ")");
            statement.execute("create table if not exists advance_transactions ("
                    + "id bigserial primary key,"
                    + "staff_id text not null references staff(id) on delete cascade,"
                    + "transaction_date date not null,"
                    + "type text not null,"
                    + "amount numeric(10,2) not null,"
                    + "balance_after numeric(10,2) not null"
                    + ")");
            statement.execute("create table if not exists public_holidays ("
                    + "holiday_date date primary key,"
                    + "title text not null default 'Public holiday'"
                    + ")");
        }
    }

    private void loadPostgres() throws Exception {
        staff.clear();
        try (Connection connection = db()) {
            try (PreparedStatement statement = connection.prepareStatement("select * from staff order by id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Staff employee = new Staff(result.getString("id"), result.getString("full_name"),
                            result.getString("nickname"), result.getString("phone"), result.getDouble("daily_wage"));
                    employee.role = result.getString("role");
                    employee.dob = dateString(result, "dob");
                    employee.joiningDate = dateString(result, "joining_date");
                    employee.emergencyContact = result.getString("emergency_contact");
                    employee.loginPhones = splitLoginAliases(result.getString("login_aliases"));
                    employee.advanceBalance = result.getDouble("advance_balance");
                    employee.lastSalaryAmount = result.getDouble("last_salary_amount");
                    employee.lastSalaryDate = dateString(result, "last_salary_date");
                    employee.lastRepaymentDate = dateString(result, "last_repayment_date");
                    employee.photoBase64 = result.getString("photo_base64") == null ? "" : result.getString("photo_base64");
                    staff.put(employee.id, employee);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("select * from attendance order by attendance_date");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Staff employee = staff.get(result.getString("staff_id"));
                    if (employee != null) {
                        employee.attendance.put(dateString(result, "attendance_date"),
                                new Attendance(result.getString("status"), result.getDouble("day_value"), result.getDouble("earned_wage")));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("select * from leaves order by id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Staff employee = staff.get(result.getString("staff_id"));
                    if (employee != null) {
                        employee.leaves.add(new Leave(dateString(result, "from_date"), dateString(result, "to_date"),
                                result.getString("reason"), result.getString("status")));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("select * from salary_payments order by paid_date,id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Staff employee = staff.get(result.getString("staff_id"));
                    if (employee != null) employee.salaryPayments.add(new SalaryPayment(dateString(result, "paid_date"), result.getDouble("amount")));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("select * from advance_transactions order by transaction_date,id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Staff employee = staff.get(result.getString("staff_id"));
                    if (employee != null) employee.advanceTransactions.add(new AdvanceTransaction(dateString(result, "transaction_date"),
                            result.getString("type"), result.getDouble("amount"), result.getDouble("balance_after")));
                }
            }
            publicHolidays.clear();
            try (PreparedStatement statement = connection.prepareStatement("select holiday_date from public_holidays order by holiday_date");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) publicHolidays.add(dateString(result, "holiday_date"));
            }
        }
    }

    private void savePostgres() throws Exception {
        try (Connection connection = db()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("delete from advance_transactions");
                statement.executeUpdate("delete from salary_payments");
                statement.executeUpdate("delete from leaves");
                statement.executeUpdate("delete from attendance");
                statement.executeUpdate("delete from staff");
                statement.executeUpdate("delete from public_holidays");
            }
            try (PreparedStatement employees = connection.prepareStatement(
                    "insert into staff (id,role,full_name,nickname,phone,dob,joining_date,emergency_contact,login_aliases,daily_wage,advance_balance,last_salary_amount,last_salary_date,last_repayment_date,photo_base64) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement attendance = connection.prepareStatement(
                    "insert into attendance (staff_id,attendance_date,status,day_value,earned_wage) values (?,?,?,?,?)");
                 PreparedStatement leaves = connection.prepareStatement(
                    "insert into leaves (staff_id,from_date,to_date,reason,status) values (?,?,?,?,?)");
                 PreparedStatement salary = connection.prepareStatement(
                    "insert into salary_payments (staff_id,paid_date,amount,month_key) values (?,?,?,?)");
                 PreparedStatement advance = connection.prepareStatement(
                    "insert into advance_transactions (staff_id,transaction_date,type,amount,balance_after) values (?,?,?,?,?)");
                 PreparedStatement holidays = connection.prepareStatement(
                    "insert into public_holidays (holiday_date,title) values (?,'Public holiday')")) {
                for (Staff employee : staff.values()) {
                    employees.setString(1, employee.id);
                    employees.setString(2, employee.role);
                    employees.setString(3, employee.name);
                    employees.setString(4, employee.nickname);
                    employees.setString(5, employee.phone);
                    setDate(employees, 6, employee.dob);
                    setDate(employees, 7, employee.joiningDate);
                    employees.setString(8, employee.emergencyContact);
                    employees.setString(9, loginAliasText(employee));
                    employees.setDouble(10, employee.dailyWage);
                    employees.setDouble(11, employee.advanceBalance);
                    employees.setDouble(12, employee.lastSalaryAmount);
                    setDate(employees, 13, employee.lastSalaryDate);
                    setDate(employees, 14, employee.lastRepaymentDate);
                    employees.setString(15, employee.photoBase64);
                    employees.executeUpdate();
                    for (Map.Entry<String, Attendance> entry : employee.attendance.entrySet()) {
                        attendance.setString(1, employee.id);
                        setDate(attendance, 2, entry.getKey());
                        attendance.setString(3, entry.getValue().status);
                        attendance.setDouble(4, entry.getValue().dayValue);
                        attendance.setDouble(5, entry.getValue().earnedWage);
                        attendance.executeUpdate();
                    }
                    for (Leave leave : employee.leaves) {
                        leaves.setString(1, employee.id);
                        setDate(leaves, 2, leave.from);
                        setDate(leaves, 3, leave.to);
                        leaves.setString(4, leave.reason);
                        leaves.setString(5, leave.status);
                        leaves.executeUpdate();
                    }
                    for (SalaryPayment payment : employee.salaryPayments) {
                        salary.setString(1, employee.id);
                        setDate(salary, 2, payment.date);
                        salary.setDouble(3, payment.amount);
                        salary.setString(4, isBlank(payment.date) ? "" : YearMonth.from(LocalDate.parse(payment.date)).toString());
                        salary.executeUpdate();
                    }
                    for (AdvanceTransaction transaction : employee.advanceTransactions) {
                        advance.setString(1, employee.id);
                        setDate(advance, 2, transaction.date);
                        advance.setString(3, transaction.type);
                        advance.setDouble(4, transaction.amount);
                        advance.setDouble(5, transaction.balanceAfter);
                        advance.executeUpdate();
                    }
                }
                for (String holiday : publicHolidays) {
                    setDate(holidays, 1, holiday);
                    holidays.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    private String dateString(ResultSet result, String column) throws Exception {
        Date date = result.getDate(column);
        return date == null ? "" : date.toLocalDate().toString();
    }

    private void setDate(PreparedStatement statement, int index, String value) throws Exception {
        LocalDate date = parseDate(value);
        if (date == null) statement.setNull(index, java.sql.Types.DATE);
        else statement.setDate(index, Date.valueOf(date));
    }

    private void home(HttpExchange exchange) throws IOException {
        if (!isAdminSession(exchange)) {
            send(exchange, 200, "text/html", loginHtml());
            return;
        }
        send(exchange, 200, "text/html", html());
    }

    private void state(HttpExchange exchange) throws IOException {
        sendJson(exchange, stateJson(""));
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        String phone = last10(body.getOrDefault("phone", ""));
        Staff employee = staff.values().stream()
                .filter(person -> "admin".equals(person.role))
                .filter(person -> matchesLoginPhone(person, phone))
                .findFirst()
                .orElse(null);
        if (employee == null) {
            employee = staff.values().stream()
                .filter(person -> matchesLoginPhone(person, phone))
                .findFirst()
                .orElse(null);
        }
        if (employee == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Phone not registered\"}");
            return;
        }
        sendJson(exchange, "{\"ok\":true,\"staffId\":\"" + json(employee.id) + "\",\"role\":\"" + json(employee.role)
                + "\",\"name\":\"" + json(employee.name) + "\"}");
    }

    private void adminLogin(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        if ("admin".equals(username) && "admin".equals(password)) {
            exchange.getResponseHeaders().add("Set-Cookie", "adminAuth=admin; Path=/; HttpOnly; SameSite=Lax");
            sendJson(exchange, "{\"ok\":true}");
            return;
        }
        sendJson(exchange, "{\"ok\":false,\"message\":\"Invalid admin login\"}");
    }

    private void adminLogout(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", "adminAuth=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        sendJson(exchange, "{\"ok\":true}");
    }

    private void attendance(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Staff not found\"}");
            return;
        }
        boolean adminOverride = "true".equalsIgnoreCase(body.getOrDefault("adminOverride", ""));
        LocalDate attendanceDate = adminOverride ? parseDate(body.getOrDefault("date", "")) : LocalDate.now();
        if (attendanceDate == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Invalid attendance date\"}");
            return;
        }
        if (adminOverride) {
            String status = normalizeAttendanceStatus(body.getOrDefault("status", ""));
            if (isBlank(status)) {
                sendJson(exchange, "{\"ok\":false,\"message\":\"Invalid attendance status\"}");
                return;
            }
            double dayValue = attendanceDayValue(status, body.getOrDefault("dayValue", ""));
            Attendance previous = employee.attendance.get(attendanceDate.toString());
            employee.attendance.put(attendanceDate.toString(), new Attendance(status, dayValue, employee.dailyWage));
            applyTodayToMonthlySummary(employee, attendanceDate, previous, status);
            save();
            sendJson(exchange, stateJson(employee.name + " attendance updated for " + attendanceDate));
            return;
        }
        LocalDate todayDate = LocalDate.now();
        if (isSunday(todayDate)) {
            Attendance previous = employee.attendance.get(todayDate.toString());
            employee.attendance.put(todayDate.toString(), new Attendance("Sunday holiday", 0, employee.dailyWage));
            applyTodayToMonthlySummary(employee, todayDate, previous, "Sunday holiday");
            save();
            sendJson(exchange, stateJson("Sunday is a weekly holiday and is not counted as salary day"));
            return;
        }
        if (isPublicHoliday(todayDate)) {
            Attendance previous = employee.attendance.get(todayDate.toString());
            employee.attendance.put(todayDate.toString(), new Attendance("Paid holiday", 1.0, employee.dailyWage));
            applyTodayToMonthlySummary(employee, todayDate, previous, "Paid holiday");
            save();
            sendJson(exchange, stateJson("Public holiday counted as paid day"));
            return;
        }
        if (hasApprovedLeave(employee, todayDate)) {
            Attendance previous = employee.attendance.get(todayDate.toString());
            employee.attendance.put(todayDate.toString(), new Attendance("Absent", 0, employee.dailyWage));
            applyTodayToMonthlySummary(employee, todayDate, previous, "Absent");
            save();
            sendJson(exchange, stateJson(employee.name + " is on approved leave today"));
            return;
        }
        String status = body.getOrDefault("status", "");
        if (isBlank(status)) status = LocalTime.now().isAfter(LocalTime.of(10, 30)) ? "Half day" : "Full day";
        double dayValue = attendanceDayValue(status, body.getOrDefault("dayValue", ""));
        Attendance previous = employee.attendance.get(todayDate.toString());
        employee.attendance.put(todayDate.toString(), new Attendance(status, dayValue, employee.dailyWage));
        applyTodayToMonthlySummary(employee, todayDate, previous, status);
        save();
        sendJson(exchange, stateJson(employee.name + " marked " + status));
    }

    private void leave(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Staff not found\"}");
            return;
        }
        LocalDate from = parseDate(body.getOrDefault("fromDate", ""));
        LocalDate to = parseDate(body.getOrDefault("toDate", ""));
        if (from == null || to == null || from.isBefore(LocalDate.now()) || to.isBefore(from)) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Invalid leave dates\"}");
            return;
        }
        if (hasOverlappingLeave(employee, from, to)) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Leave already exists for selected dates\"}");
            return;
        }
        employee.leaves.add(new Leave(body.getOrDefault("fromDate", ""), body.getOrDefault("toDate", ""),
                body.getOrDefault("reason", ""), "Pending"));
        save();
        sendJson(exchange, stateJson("Leave submitted"));
    }

    private void leaveStatus(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        int index = parseInt(body.getOrDefault("index", "-1"));
        if (employee != null && index >= 0 && index < employee.leaves.size()) {
            employee.leaves.get(index).status = body.getOrDefault("status", "Pending");
            save();
        }
        sendJson(exchange, stateJson("Leave updated"));
    }

    private void photo(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee != null) {
            employee.photoBase64 = body.getOrDefault("photo", "");
            save();
        }
        sendJson(exchange, stateJson("Photo updated"));
    }

    private void advance(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee != null) {
            double amount = parseDouble(body.getOrDefault("amount", "0"));
            if (amount > 0) {
                employee.advanceBalance += amount;
                employee.advanceTransactions.add(new AdvanceTransaction(LocalDate.now().toString(), "ADVANCE", amount, employee.advanceBalance));
                save();
            }
        }
        sendJson(exchange, stateJson("Advance added"));
    }

    private void repayment(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee != null) {
            double amount = parseDouble(body.getOrDefault("amount", "0"));
            if (amount > 0) {
                employee.advanceBalance = Math.max(0, employee.advanceBalance - amount);
                employee.lastRepaymentDate = LocalDate.now().toString();
                employee.advanceTransactions.add(new AdvanceTransaction(employee.lastRepaymentDate, "REPAYMENT", amount, employee.advanceBalance));
                save();
            }
        }
        sendJson(exchange, stateJson("Repayment applied"));
    }

    private void salaryPayment(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        double amount = parseDouble(body.getOrDefault("amount", "0"));
        if (employee != null && amount > 0) {
            String date = LocalDate.now().toString();
            employee.lastSalaryAmount = amount;
            employee.lastSalaryDate = date;
            employee.salaryPayments.add(new SalaryPayment(date, amount));
            save();
        }
        sendJson(exchange, stateJson("Salary payment saved"));
    }

    private void dailyWage(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        double amount = parseDouble(body.getOrDefault("amount", "0"));
        if (employee != null && amount > 0) {
            freezeAttendanceWages(employee);
            employee.dailyWage = amount;
            save();
        }
        sendJson(exchange, stateJson("Salary updated"));
    }

    private void staffUpdate(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        String id = body.getOrDefault("staffId", "").trim();
        if (isBlank(id)) id = nextEmployeeId();
        Staff employee = staff.get(id);
        if (employee == null) {
            String name = body.getOrDefault("name", "").trim();
            if (isBlank(name)) {
                sendJson(exchange, "{\"ok\":false,\"message\":\"Name required\"}");
                return;
            }
            employee = new Staff(id, name, firstName(name), body.getOrDefault("phone", ""), parseDouble(body.getOrDefault("dailyWage", "650")));
            employee.dob = body.getOrDefault("dob", "1995-01-01");
            employee.joiningDate = body.getOrDefault("joiningDate", LocalDate.now().toString());
            employee.emergencyContact = body.getOrDefault("emergencyContact", "Not added yet");
            staff.put(employee.id, employee);
        } else {
            String name = body.getOrDefault("name", "").trim();
            if (!isBlank(name)) {
                employee.name = name;
                employee.nickname = firstName(name);
            }
            if (body.containsKey("phone")) employee.phone = body.getOrDefault("phone", "");
            if (body.containsKey("emergencyContact")) employee.emergencyContact = body.getOrDefault("emergencyContact", "");
            if (body.containsKey("dob")) employee.dob = body.getOrDefault("dob", "");
            if (body.containsKey("joiningDate")) employee.joiningDate = body.getOrDefault("joiningDate", "");
            double wage = parseDouble(body.getOrDefault("dailyWage", "0"));
            if (wage > 0) {
                freezeAttendanceWages(employee);
                employee.dailyWage = wage;
            }
        }
        save();
        sendJson(exchange, stateJson("Staff saved"));
    }

    private void monthAttendance(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Staff not found\"}");
            return;
        }
        int full = Math.max(0, parseInt(body.getOrDefault("fullDays", "0")));
        int half = Math.max(0, parseInt(body.getOrDefault("halfDays", "0")));
        int absent = Math.max(0, parseInt(body.getOrDefault("absentDays", "0")));
        YearMonth month = YearMonth.now();
        employee.attendance.entrySet().removeIf(entry -> YearMonth.from(LocalDate.parse(entry.getKey())).equals(month));
        employee.summaryMonth = month.toString();
        employee.summaryFullDays = full;
        employee.summaryHalfDays = half;
        employee.summaryAbsentDays = absent;
        save();
        sendJson(exchange, stateJson("Month attendance updated"));
    }

    private void holiday(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        LocalDate date = parseDate(body.getOrDefault("date", ""));
        if (date == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Invalid holiday date\"}");
            return;
        }
        if (isSunday(date)) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Sunday is already weekly holiday and not paid extra\"}");
            return;
        }
        if ("remove".equalsIgnoreCase(body.getOrDefault("action", ""))) {
            publicHolidays.remove(date.toString());
            for (Staff employee : staff.values()) {
                Attendance attendance = employee.attendance.get(date.toString());
                if (attendance != null && "Paid holiday".equals(attendance.status)) {
                    employee.attendance.remove(date.toString());
                }
            }
            save();
            sendJson(exchange, stateJson("Public holiday removed"));
            return;
        }
        publicHolidays.add(date.toString());
        for (Staff employee : staff.values()) {
            if ("employee".equals(employee.role) && !employee.attendance.containsKey(date.toString())) {
                employee.attendance.put(date.toString(), new Attendance("Paid holiday", 1.0, employee.dailyWage));
            }
        }
        save();
        sendJson(exchange, stateJson("Public holiday added"));
    }

    private String stateJson(String message) {
        String staffJson = staff.values().stream().map(this::staffJson).collect(Collectors.joining(","));
        String holidays = publicHolidays.stream().map(date -> "\"" + json(date) + "\"").collect(Collectors.joining(","));
        return "{\"ok\":true,\"message\":\"" + json(message) + "\",\"publicHolidays\":[" + holidays + "],\"staff\":[" + staffJson + "]}";
    }

    private String staffJson(Staff employee) {
        normalizeApprovedLeaveAbsence(employee, LocalDate.now());
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        double monthDays = employee.summaryForCurrentMonth() ? employee.summaryFullDays + employee.summaryHalfDays * 0.5
                : employee.attendance.entrySet().stream()
                .filter(entry -> !LocalDate.parse(entry.getKey()).isBefore(monthStart))
                .filter(entry -> countsForSalary(LocalDate.parse(entry.getKey())))
                .mapToDouble(entry -> entry.getValue().dayValue)
                .sum();
        double earned = employee.summaryForCurrentMonth() ? monthDays * employee.dailyWage
                : employee.attendance.entrySet().stream()
                .filter(entry -> !LocalDate.parse(entry.getKey()).isBefore(monthStart))
                .filter(entry -> countsForSalary(LocalDate.parse(entry.getKey())))
                .mapToDouble(entry -> earnedAmount(entry.getValue()))
                .sum();
        double remaining = unpaidSinceLastSalary(employee);
        double weeklyCap = employee.dailyWage * 6 * 0.5;
        String leaves = employee.leaves.stream().map(leave -> "{\"from\":\"" + json(leave.from) + "\",\"to\":\""
                + json(leave.to) + "\",\"reason\":\"" + json(leave.reason) + "\",\"status\":\"" + json(leave.status) + "\"}")
                .collect(Collectors.joining(","));
        String advanceLogs = employee.advanceTransactions.stream()
                .map(transaction -> "{\"date\":\"" + json(transaction.date) + "\",\"type\":\"" + json(transaction.type)
                        + "\",\"amount\":" + money(transaction.amount) + ",\"balance\":" + money(transaction.balanceAfter) + "}")
                .collect(Collectors.joining(","));
        String salaryLogs = employee.salaryPayments.stream()
                .map(payment -> "{\"date\":\"" + json(payment.date) + "\",\"amount\":" + money(payment.amount) + "}")
                .collect(Collectors.joining(","));
        String attendanceRows = attendanceRows(employee).entrySet().stream()
                .map(entry -> "{\"date\":\"" + json(entry.getKey()) + "\",\"status\":\"" + json(entry.getValue().status)
                        + "\",\"dayValue\":" + money(entry.getValue().dayValue) + "}")
                .collect(Collectors.joining(","));
        String history = historyJson(employee);
        String today = employee.attendance.containsKey(LocalDate.now().toString())
                ? employee.attendance.get(LocalDate.now().toString()).status
                : "Not marked";
        boolean attendanceMarked = employee.attendance.containsKey(LocalDate.now().toString());
        return "{"
                + "\"id\":\"" + json(employee.id) + "\","
                + "\"role\":\"" + json(employee.role) + "\","
                + "\"name\":\"" + json(employee.name) + "\","
                + "\"nickname\":\"" + json(employee.nickname) + "\","
                + "\"phone\":\"" + json(employee.phone) + "\","
                + "\"dob\":\"" + json(employee.dob) + "\","
                + "\"joiningDate\":\"" + json(employee.joiningDate) + "\","
                + "\"emergencyContact\":\"" + json(employee.emergencyContact) + "\","
                + "\"photo\":\"" + json(employee.photoBase64) + "\","
                + "\"dailyWage\":" + money(employee.dailyWage) + ","
                + "\"today\":\"" + json(today) + "\","
                + "\"attendanceMarked\":" + attendanceMarked + ","
                + "\"monthDays\":" + money(monthDays) + ","
                + "\"fullDays\":" + employee.summaryFullDays + ","
                + "\"halfDays\":" + employee.summaryHalfDays + ","
                + "\"absentDays\":" + employee.summaryAbsentDays + ","
                + "\"earned\":" + money(earned) + ","
                + "\"paid\":" + money(employee.lastSalaryAmount) + ","
                + "\"remaining\":" + money(remaining) + ","
                + "\"lastSalaryDate\":\"" + json(employee.lastSalaryDate) + "\","
                + "\"advance\":" + money(employee.advanceBalance) + ","
                + "\"weeklyCap\":" + money(weeklyCap) + ","
                + "\"lastRepayment\":\"" + json(employee.lastRepaymentDate) + "\","
                + "\"leaves\":[" + leaves + "],"
                + "\"advanceLogs\":[" + advanceLogs + "],"
                + "\"salaryLogs\":[" + salaryLogs + "],"
                + "\"attendanceRows\":[" + attendanceRows + "],"
                + "\"history\":[" + history + "]"
                + "}";
    }

    private String historyJson(Staff employee) {
        TreeSet<YearMonth> months = new TreeSet<>(Comparator.reverseOrder());
        employee.attendance.keySet().forEach(date -> months.add(YearMonth.from(LocalDate.parse(date))));
        employee.leaves.forEach(leave -> {
            if (!isBlank(leave.from)) months.add(YearMonth.from(LocalDate.parse(leave.from)));
        });
        employee.salaryPayments.forEach(payment -> {
            if (!isBlank(payment.date)) months.add(YearMonth.from(LocalDate.parse(payment.date)));
        });
        months.add(YearMonth.now());

        return months.stream().map(month -> {
            boolean summaryMonth = employee.summaryForMonth(month);
            double days = summaryMonth ? employee.summaryFullDays + employee.summaryHalfDays * 0.5 : employee.attendance.entrySet().stream()
                    .filter(entry -> YearMonth.from(LocalDate.parse(entry.getKey())).equals(month))
                    .filter(entry -> countsForSalary(LocalDate.parse(entry.getKey())))
                    .mapToDouble(entry -> entry.getValue().dayValue)
                    .sum();
            long leavesApplied = employee.leaves.stream()
                    .filter(leave -> !isBlank(leave.from) && YearMonth.from(LocalDate.parse(leave.from)).equals(month))
                    .count();
            long approvedLeaveDays = approvedLeaveDatesInMonth(employee, month);
            double paid = employee.salaryPayments.stream()
                    .filter(payment -> !isBlank(payment.date) && YearMonth.from(LocalDate.parse(payment.date)).equals(month))
                    .mapToDouble(payment -> payment.amount)
                    .sum();
            double earned = summaryMonth ? days * employee.dailyWage : employee.attendance.entrySet().stream()
                    .filter(entry -> YearMonth.from(LocalDate.parse(entry.getKey())).equals(month))
                    .filter(entry -> countsForSalary(LocalDate.parse(entry.getKey())))
                    .mapToDouble(entry -> earnedAmount(entry.getValue()))
                    .sum();
            return "{\"month\":\"" + json(month.toString()) + "\","
                    + "\"days\":" + money(days) + ","
                    + "\"earned\":" + money(earned) + ","
                    + "\"paid\":" + money(paid) + ","
                    + "\"leaves\":" + approvedLeaveDays + ","
                    + "\"appliedLeaves\":" + leavesApplied + ","
                    + "\"approvedLeaves\":" + approvedLeaveDays + "}";
        }).collect(Collectors.joining(","));
    }

    private Map<String, Attendance> attendanceRows(Staff employee) {
        Map<String, Attendance> rows = new LinkedHashMap<>();
        if (employee.summaryForCurrentMonth()) {
            int full = employee.summaryFullDays;
            int half = employee.summaryHalfDays;
            int absent = employee.summaryAbsentDays;
            YearMonth month = YearMonth.now();
            LocalDate cursor = month.atDay(1);
            LocalDate end = LocalDate.now().isBefore(month.atEndOfMonth()) ? LocalDate.now() : month.atEndOfMonth();
            while (!cursor.isAfter(end)) {
                if (!isSunday(cursor)) {
                    String key = cursor.toString();
                    if (employee.attendance.containsKey(key)) {
                        Attendance existing = employee.attendance.get(key);
                        rows.put(key, existing);
                        if ("Full day".equals(existing.status) || "Paid holiday".equals(existing.status)) {
                            full = Math.max(0, full - 1);
                        } else if ("Half day".equals(existing.status)) {
                            half = Math.max(0, half - 1);
                        } else if ("Absent".equals(existing.status)) {
                            absent = Math.max(0, absent - 1);
                        }
                    } else if (full > 0) {
                        rows.put(key, new Attendance(isPublicHoliday(cursor) ? "Paid holiday" : "Full day", 1.0, employee.dailyWage));
                        full--;
                    } else if (half > 0) {
                        rows.put(key, new Attendance("Half day", 0.5, employee.dailyWage));
                        half--;
                    } else if (absent > 0) {
                        rows.put(key, new Attendance("Absent", 0, employee.dailyWage));
                        absent--;
                    }
                }
                cursor = cursor.plusDays(1);
            }
        }
        rows.putAll(employee.attendance);
        return rows;
    }

    private void normalizeApprovedLeaveAbsence(Staff employee, LocalDate date) {
        Attendance attendance = employee.attendance.get(date.toString());
        if (!countsForSalary(date) && attendance != null && attendance.dayValue != 0) {
            employee.attendance.put(date.toString(), new Attendance("Sunday holiday", 0, employee.dailyWage));
            save();
        } else if (hasApprovedLeave(employee, date) && !isPublicHoliday(date) && (attendance == null || !"Absent".equals(attendance.status))) {
            employee.attendance.put(date.toString(), new Attendance("Absent", 0, employee.dailyWage));
            save();
        } else if (isPublicHoliday(date) && (attendance == null || attendance.dayValue == 0)) {
            employee.attendance.put(date.toString(), new Attendance("Paid holiday", 1.0, employee.dailyWage));
            save();
        }
    }

    private void applyTodayToMonthlySummary(Staff employee, LocalDate date, Attendance previous, String newStatus) {
        YearMonth month = YearMonth.now();
        if (!YearMonth.from(date).equals(month)) {
            return;
        }
        if (!employee.summaryForMonth(month)) {
            employee.summaryMonth = month.toString();
        }
        String key = date.toString();
        if (previous != null && employee.summaryAppliedDates.contains(key)) removeSummaryStatus(employee, previous.status);
        addSummaryStatus(employee, newStatus);
        employee.summaryAppliedDates.add(key);
    }

    private void removeSummaryStatus(Staff employee, String status) {
        if ("Full day".equals(status) || "Paid holiday".equals(status)) {
            employee.summaryFullDays = Math.max(0, employee.summaryFullDays - 1);
        } else if ("Half day".equals(status)) {
            employee.summaryHalfDays = Math.max(0, employee.summaryHalfDays - 1);
        } else if ("Absent".equals(status)) {
            employee.summaryAbsentDays = Math.max(0, employee.summaryAbsentDays - 1);
        }
    }

    private void addSummaryStatus(Staff employee, String status) {
        if ("Full day".equals(status) || "Paid holiday".equals(status)) {
            employee.summaryFullDays++;
        } else if ("Half day".equals(status)) {
            employee.summaryHalfDays++;
        } else if ("Absent".equals(status)) {
            employee.summaryAbsentDays++;
        }
    }

    private void freezeAttendanceWages(Staff employee) {
        for (Attendance attendance : employee.attendance.values()) {
            if (attendance.earnedWage <= 0) attendance.earnedWage = DEFAULT_DAILY_WAGE;
        }
    }

    private double earnedAmount(Attendance attendance) {
        double wage = attendance.earnedWage > 0 ? attendance.earnedWage : DEFAULT_DAILY_WAGE;
        return attendance.dayValue * wage;
    }

    private double salaryPaidInMonth(Staff employee, YearMonth month) {
        return employee.salaryPayments.stream()
                .filter(payment -> !isBlank(payment.date) && YearMonth.from(LocalDate.parse(payment.date)).equals(month))
                .mapToDouble(payment -> payment.amount)
                .sum();
    }

    private double unpaidSinceLastSalary(Staff employee) {
        LocalDate paidThrough = parseDate(employee.lastSalaryDate);
        LocalDate start = paidThrough == null ? LocalDate.now().withDayOfMonth(1) : paidThrough.plusDays(1);
        LocalDate today = LocalDate.now();
        if (employee.summaryForCurrentMonth()) {
            double afterPaidWorkingDays = workingDaysBetween(start, today);
            double availablePaidDays = employee.summaryFullDays + employee.summaryHalfDays * 0.5;
            double currentMonthWorkingDays = workingDaysBetween(LocalDate.now().withDayOfMonth(1), today);
            double beforeOrOnPaidWorkingDays = Math.max(0, currentMonthWorkingDays - afterPaidWorkingDays);
            double estimatedUnpaidDays = Math.max(0, Math.min(afterPaidWorkingDays, availablePaidDays - beforeOrOnPaidWorkingDays));
            return estimatedUnpaidDays * employee.dailyWage;
        }
        return employee.attendance.entrySet().stream()
                .filter(entry -> {
                    LocalDate date = LocalDate.parse(entry.getKey());
                    return !date.isBefore(start) && !date.isAfter(today) && countsForSalary(date);
                })
                .mapToDouble(entry -> earnedAmount(entry.getValue()))
                .sum();
    }

    private double workingDaysBetween(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) return 0;
        double days = 0;
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            if (countsForSalary(cursor)) days++;
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    private boolean countsForSalary(LocalDate date) {
        return !isSunday(date);
    }

    private boolean isSunday(LocalDate date) {
        return date.getDayOfWeek().getValue() == 7;
    }

    private boolean isPublicHoliday(LocalDate date) {
        return publicHolidays.contains(date.toString()) && !isSunday(date);
    }

    private boolean hasApprovedLeave(Staff employee, LocalDate date) {
        return employee.leaves.stream().anyMatch(leave -> "Approved".equals(leave.status) && containsDate(leave, date));
    }

    private boolean hasOverlappingLeave(Staff employee, LocalDate from, LocalDate to) {
        return employee.leaves.stream()
                .filter(leave -> !"Rejected".equals(leave.status))
                .anyMatch(leave -> overlaps(leave, from, to));
    }

    private boolean overlaps(Leave leave, LocalDate from, LocalDate to) {
        LocalDate existingFrom = parseDate(leave.from);
        LocalDate existingTo = parseDate(leave.to);
        return existingFrom != null && existingTo != null && !to.isBefore(existingFrom) && !from.isAfter(existingTo);
    }

    private boolean containsDate(Leave leave, LocalDate date) {
        LocalDate from = parseDate(leave.from);
        LocalDate to = parseDate(leave.to);
        return from != null && to != null && !date.isBefore(from) && !date.isAfter(to);
    }

    private long leaveDaysInMonth(Leave leave, YearMonth month) {
        LocalDate from = parseDate(leave.from);
        LocalDate to = parseDate(leave.to);
        if (from == null || to == null) return 0;
        LocalDate start = from.isBefore(month.atDay(1)) ? month.atDay(1) : from;
        LocalDate end = to.isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : to;
        if (end.isBefore(start)) return 0;
        return end.toEpochDay() - start.toEpochDay() + 1;
    }

    private long approvedLeaveDatesInMonth(Staff employee, YearMonth month) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        employee.leaves.stream()
                .filter(leave -> "Approved".equals(leave.status))
                .forEach(leave -> {
                    LocalDate from = parseDate(leave.from);
                    LocalDate to = parseDate(leave.to);
                    if (from == null || to == null) return;
                    LocalDate cursor = from.isBefore(month.atDay(1)) ? month.atDay(1) : from;
                    LocalDate end = to.isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : to;
                    while (!cursor.isAfter(end)) {
                        dates.add(cursor);
                        cursor = cursor.plusDays(1);
                    }
                });
        return dates.size();
    }

    private String html() {
        Path adminPage = Paths.get("web", "admin.html");
        try {
            if (Files.exists(adminPage)) {
                return new String(Files.readAllBytes(adminPage), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            System.out.println("Could not read admin page: " + exception.getMessage());
        }
        return "<!doctype html><html><body><h1>Admin Dashboard</h1><p>web/admin.html not found.</p></body></html>";
    }

    private String loginHtml() {
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Admin Login</title><style>"
                + "body{margin:0;min-height:100vh;display:grid;place-items:center;background:#eef3f5;font-family:Arial;color:#17212b}"
                + ".box{width:min(360px,calc(100% - 32px));background:white;border-radius:14px;padding:22px;box-shadow:0 18px 45px #18313f22}"
                + "h1{margin:0 0 6px;font-size:24px}p{margin:0 0 18px;color:#607080}label{display:block;font-size:12px;font-weight:700;color:#607080;margin:12px 0 5px}"
                + "input{width:100%;box-sizing:border-box;border:1px solid #d9e2ec;border-radius:8px;padding:11px;font-size:15px}"
                + "button{width:100%;border:0;border-radius:8px;background:#126d5c;color:white;font-weight:800;padding:12px;margin-top:16px;font-size:15px}"
                + ".err{color:#9a2b2b;font-size:13px;min-height:18px;margin-top:10px}</style></head><body>"
                + "<form class=\"box\" onsubmit=\"login(event)\"><h1>Admin Login</h1><p>Attendance control panel</p>"
                + "<label>User ID</label><input id=\"u\" autocomplete=\"username\" autofocus>"
                + "<label>Password</label><input id=\"p\" type=\"password\" autocomplete=\"current-password\">"
                + "<button>Login</button><div class=\"err\" id=\"err\"></div></form>"
                + "<script>async function login(e){e.preventDefault();const r=await fetch('/api/admin-login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u.value,password:p.value})});const j=await r.json();if(j.ok)location.reload();else err.textContent=j.message||'Invalid login';}</script>"
                + "</body></html>";
    }

    private boolean isAdminSession(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) return false;
        return cookies.stream().anyMatch(cookie -> Arrays.stream(cookie.split(";"))
                .map(String::trim)
                .anyMatch(value -> "adminAuth=admin".equals(value)));
    }

    private Map<String, String> body(HttpExchange exchange) throws IOException {
        String raw = new String(readAllBytes(exchange), StandardCharsets.UTF_8).trim();
        Map<String, String> map = new LinkedHashMap<>();
        if (raw.length() < 2) return map;
        String inner = raw.substring(1, raw.length() - 1);
        for (String pair : inner.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) map.put(clean(parts[0]), clean(parts[1]));
        }
        return map;
    }

    private byte[] readAllBytes(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private String clean(String value) {
        return value.trim().replaceAll("^\"", "").replaceAll("\"$", "").replace("\\\"", "\"");
    }

    private void sendJson(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json", body);
    }

    private void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String money(double value) {
        return String.format("%.2f", value);
    }

    private double parseDouble(String value) {
        try { return Double.parseDouble(value); } catch (Exception exception) { return 0; }
    }

    private String normalizeAttendanceStatus(String status) {
        String normalized = status == null ? "" : status.trim();
        if ("PRESENT".equalsIgnoreCase(normalized) || "FULL_DAY".equalsIgnoreCase(normalized)
                || "Full day".equalsIgnoreCase(normalized)) {
            return "Full day";
        }
        if ("HALF_DAY".equalsIgnoreCase(normalized) || "Half day".equalsIgnoreCase(normalized)) {
            return "Half day";
        }
        if ("ABSENT".equalsIgnoreCase(normalized) || "Absent".equalsIgnoreCase(normalized)) {
            return "Absent";
        }
        if ("PAID_HOLIDAY".equalsIgnoreCase(normalized) || "Paid holiday".equalsIgnoreCase(normalized)) {
            return "Paid holiday";
        }
        if ("SUNDAY_HOLIDAY".equalsIgnoreCase(normalized) || "Sunday holiday".equalsIgnoreCase(normalized)) {
            return "Sunday holiday";
        }
        return "";
    }

    private double attendanceDayValue(String status, String explicitValue) {
        if (!isBlank(explicitValue)) {
            return parseDouble(explicitValue);
        }
        if ("Full day".equals(status) || "Paid holiday".equals(status)) return 1.0;
        if ("Half day".equals(status)) return 0.5;
        return 0;
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (Exception exception) { return -1; }
    }

    private LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); } catch (Exception exception) { return null; }
    }

    private String firstName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (isBlank(trimmed)) return "";
        return trimmed.split("\\s+")[0];
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String last10(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private boolean matchesLoginPhone(Staff employee, String phone) {
        if (isBlank(phone)) return false;
        if (last10(employee.phone).equals(phone)) return true;
        if (employee.loginPhones == null) return false;
        return employee.loginPhones.stream().anyMatch(alias -> last10(alias).equals(phone));
    }

    private List<String> splitLoginAliases(String aliases) {
        if (isBlank(aliases)) return new ArrayList<>();
        return Arrays.stream(aliases.split(","))
                .map(String::trim)
                .filter(value -> !isBlank(value))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String loginAliasText(Staff employee) {
        if (employee.loginPhones == null) return "";
        return employee.loginPhones.stream()
                .map(this::last10)
                .filter(value -> !isBlank(value))
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String nextEmployeeId() {
        int max = staff.keySet().stream()
                .filter(id -> id.startsWith("OP"))
                .mapToInt(id -> parseInt(id.substring(2)))
                .max()
                .orElse(0);
        return String.format("OP%03d", max + 1);
    }

    static class Staff implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        String name;
        String nickname;
        String phone;
        double dailyWage;
        String role = "employee";
        String dob;
        String joiningDate;
        String emergencyContact;
        List<String> loginPhones = new ArrayList<>();
        double advanceBalance;
        double lastSalaryAmount;
        String lastSalaryDate = "";
        String lastRepaymentDate = "";
        String photoBase64 = "";
        String summaryMonth = "";
        int summaryFullDays;
        int summaryHalfDays;
        int summaryAbsentDays;
        TreeSet<String> summaryAppliedDates = new TreeSet<>();
        final Map<String, Attendance> attendance = new LinkedHashMap<>();
        List<Leave> leaves = new ArrayList<>();
        List<SalaryPayment> salaryPayments = new ArrayList<>();
        List<AdvanceTransaction> advanceTransactions = new ArrayList<>();

        Staff(String id, String name, String nickname, String phone, double dailyWage) {
            this.id = id;
            this.name = name;
            this.nickname = nickname;
            this.phone = phone;
            this.dailyWage = dailyWage;
        }

        boolean summaryForCurrentMonth() {
            return summaryForMonth(YearMonth.now());
        }

        boolean summaryForMonth(YearMonth month) {
            return summaryMonth != null && summaryMonth.equals(month.toString());
        }
    }

    static class AppData implements Serializable {
        private static final long serialVersionUID = 1L;
        final Map<String, Staff> staff;
        final TreeSet<String> publicHolidays;

        AppData(Map<String, Staff> staff, TreeSet<String> publicHolidays) {
            this.staff = new LinkedHashMap<>(staff);
            this.publicHolidays = new TreeSet<>(publicHolidays);
        }
    }

    static class Attendance implements Serializable {
        private static final long serialVersionUID = 1L;
        final String status;
        final double dayValue;
        double earnedWage;

        Attendance(String status, double dayValue) {
            this(status, dayValue, 0);
        }

        Attendance(String status, double dayValue, double earnedWage) {
            this.status = status;
            this.dayValue = dayValue;
            this.earnedWage = earnedWage;
        }
    }

    static class Leave implements Serializable {
        private static final long serialVersionUID = 1L;
        final String from;
        final String to;
        final String reason;
        String status;
        Leave(String from, String to, String reason, String status) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.status = status;
        }
    }

    static class SalaryPayment implements Serializable {
        private static final long serialVersionUID = 1L;
        final String date;
        final double amount;

        SalaryPayment(String date, double amount) {
            this.date = date;
            this.amount = amount;
        }
    }

    static class AdvanceTransaction implements Serializable {
        private static final long serialVersionUID = 1L;
        final String date;
        final String type;
        final double amount;
        final double balanceAfter;

        AdvanceTransaction(String date, String type, double amount, double balanceAfter) {
            this.date = date;
            this.type = type;
            this.amount = amount;
            this.balanceAfter = balanceAfter;
        }
    }
}
