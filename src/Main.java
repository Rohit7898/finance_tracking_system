import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");
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
                if (loaded instanceof AppData data) {
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
        Staff rahul = staff.get("OP003");
        if (rahul != null && rahul.loginPhones.stream().noneMatch(phone -> last10(phone).equals("8962569527"))) {
            rahul.loginPhones.add("8962569527");
            changed = true;
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
        return DATABASE_URL != null && !DATABASE_URL.isBlank();
    }

    private Connection db() throws Exception {
        String url = DATABASE_URL;
        String user = System.getenv("PGUSER");
        String password = System.getenv("PGPASSWORD");
        if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            url = "jdbc:" + url;
        }
        if (user != null && !user.isBlank()) {
            return DriverManager.getConnection(url, user, password == null ? "" : password);
        }
        return DriverManager.getConnection(url);
    }

    private void initPostgres() throws Exception {
        try (Connection connection = db(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists staff (
                        id text primary key,
                        role text not null default 'employee',
                        full_name text not null,
                        nickname text,
                        phone text,
                        dob date,
                        joining_date date,
                        emergency_contact text,
                        login_aliases text,
                        daily_wage numeric(10,2) not null default 0,
                        advance_balance numeric(10,2) not null default 0,
                        last_salary_amount numeric(10,2) not null default 0,
                        last_salary_date date,
                        last_repayment_date date,
                        photo_base64 text
                    )
                    """);
            try {
                statement.execute("alter table staff add column if not exists login_aliases text");
            } catch (Exception ignored) {
            }
            statement.execute("""
                    create table if not exists attendance (
                        staff_id text not null references staff(id) on delete cascade,
                        attendance_date date not null,
                        status text not null,
                        day_value numeric(4,2) not null,
                        earned_wage numeric(10,2) not null,
                        source text not null default 'employee',
                        primary key (staff_id, attendance_date)
                    )
                    """);
            statement.execute("""
                    create table if not exists leaves (
                        id bigserial primary key,
                        staff_id text not null references staff(id) on delete cascade,
                        from_date date not null,
                        to_date date not null,
                        reason text,
                        status text not null default 'Pending',
                        decided_by text,
                        decided_at timestamptz
                    )
                    """);
            statement.execute("""
                    create table if not exists salary_payments (
                        id bigserial primary key,
                        staff_id text not null references staff(id) on delete cascade,
                        paid_date date not null,
                        amount numeric(10,2) not null,
                        month_key text
                    )
                    """);
            statement.execute("""
                    create table if not exists advance_transactions (
                        id bigserial primary key,
                        staff_id text not null references staff(id) on delete cascade,
                        transaction_date date not null,
                        type text not null,
                        amount numeric(10,2) not null,
                        balance_after numeric(10,2) not null
                    )
                    """);
            statement.execute("""
                    create table if not exists public_holidays (
                        holiday_date date primary key,
                        title text not null default 'Public holiday'
                    )
                    """);
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
            try (PreparedStatement employees = connection.prepareStatement("""
                    insert into staff (id,role,full_name,nickname,phone,dob,joining_date,emergency_contact,login_aliases,daily_wage,advance_balance,last_salary_amount,last_salary_date,last_repayment_date,photo_base64)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """);
                 PreparedStatement attendance = connection.prepareStatement("""
                    insert into attendance (staff_id,attendance_date,status,day_value,earned_wage) values (?,?,?,?,?)
                    """);
                 PreparedStatement leaves = connection.prepareStatement("""
                    insert into leaves (staff_id,from_date,to_date,reason,status) values (?,?,?,?,?)
                    """);
                 PreparedStatement salary = connection.prepareStatement("""
                    insert into salary_payments (staff_id,paid_date,amount,month_key) values (?,?,?,?)
                    """);
                 PreparedStatement advance = connection.prepareStatement("""
                    insert into advance_transactions (staff_id,transaction_date,type,amount,balance_after) values (?,?,?,?,?)
                    """);
                 PreparedStatement holidays = connection.prepareStatement("""
                    insert into public_holidays (holiday_date,title) values (?,'Public holiday')
                    """)) {
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
                        salary.setString(4, payment.date == null || payment.date.isBlank() ? "" : YearMonth.from(LocalDate.parse(payment.date)).toString());
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
        send(exchange, 200, "text/html", html());
    }

    private void state(HttpExchange exchange) throws IOException {
        sendJson(exchange, stateJson(""));
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        String phone = last10(body.getOrDefault("phone", ""));
        Staff employee = staff.values().stream()
                .filter(person -> matchesLoginPhone(person, phone))
                .findFirst()
                .orElse(null);
        if (employee == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Phone not registered\"}");
            return;
        }
        sendJson(exchange, "{\"ok\":true,\"staffId\":\"" + json(employee.id) + "\",\"role\":\"" + json(employee.role)
                + "\",\"name\":\"" + json(employee.name) + "\"}");
    }

    private void attendance(HttpExchange exchange) throws IOException {
        Map<String, String> body = body(exchange);
        Staff employee = staff.get(body.getOrDefault("staffId", "OP001"));
        if (employee == null) {
            sendJson(exchange, "{\"ok\":false,\"message\":\"Staff not found\"}");
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
        if (status.isBlank()) status = LocalTime.now().isAfter(LocalTime.of(10, 30)) ? "Half day" : "Full day";
        double dayValue = parseDouble(body.getOrDefault("dayValue", status.equals("Full day") ? "1.0" : status.equals("Half day") ? "0.5" : "0"));
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
        if (id.isBlank()) id = nextEmployeeId();
        Staff employee = staff.get(id);
        if (employee == null) {
            String name = body.getOrDefault("name", "").trim();
            if (name.isBlank()) {
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
            if (!name.isBlank()) {
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
            if (!leave.from.isBlank()) months.add(YearMonth.from(LocalDate.parse(leave.from)));
        });
        employee.salaryPayments.forEach(payment -> {
            if (!payment.date.isBlank()) months.add(YearMonth.from(LocalDate.parse(payment.date)));
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
                    .filter(leave -> !leave.from.isBlank() && YearMonth.from(LocalDate.parse(leave.from)).equals(month))
                    .count();
            long approvedLeaveDays = approvedLeaveDatesInMonth(employee, month);
            double paid = employee.salaryPayments.stream()
                    .filter(payment -> !payment.date.isBlank() && YearMonth.from(LocalDate.parse(payment.date)).equals(month))
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
                .filter(payment -> !payment.date.isBlank() && YearMonth.from(LocalDate.parse(payment.date)).equals(month))
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
        return """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Admin</title><style>
                body{margin:0;background:#f4f6f8;font-family:Arial;color:#17212b}.top{position:sticky;top:0;background:white;padding:12px 16px;border-bottom:1px solid #dde5ec;z-index:2}
                h1{font-size:20px;margin:0}.wrap{max-width:980px;margin:auto;padding:10px}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-top:10px}.staffgrid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
                .card{background:white;border-radius:9px;padding:12px;margin:10px 0;box-shadow:0 1px 8px #00000012}.metric{padding:10px;margin:0}.green{background:#e8f6f2}.red{background:#ffebeb}.blue{background:#e8f1f9}.purple{background:#f6eefa}
                b{font-size:18px}small{color:#607080}.profile{display:flex;gap:10px;align-items:center}.profile h2{font-size:18px;margin:0 0 3px}
                .avatar{width:50px;height:50px;border-radius:50%;object-fit:cover;background:#2f6f73;color:white;display:grid;place-items:center;font-weight:800;font-size:16px}
                .tile{margin:0;cursor:pointer;transition:.15s transform,.15s box-shadow}.tile:hover{transform:translateY(-1px);box-shadow:0 5px 18px #00000018}.badge{display:inline-block;border-radius:999px;padding:3px 8px;font-size:12px;font-weight:700;background:#e8f1f9;color:#1f5d8f}.badge.warn{background:#fff4de;color:#8a4b12}.badge.bad{background:#ffebeb;color:#9a2b2b}.summary{position:sticky;top:53px;z-index:1;background:#f4f6f8;padding-bottom:4px}
                .tools{display:grid;grid-template-columns:1fr;gap:8px;margin-top:10px}.tool{display:grid;grid-template-columns:1.1fr 1fr auto;gap:6px;background:#f8fafc;border:1px solid #d9e2ec;border-radius:9px;padding:6px}.atttool{display:grid;grid-template-columns:repeat(3,1fr) auto;gap:6px;background:#f8fafc;border:1px solid #d9e2ec;border-radius:9px;padding:6px}
                .holidaybar{display:grid;grid-template-columns:1fr auto;gap:8px;align-items:end}.chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}.chip{background:#fff4de;color:#8a4b12;border-radius:999px;padding:4px 9px;font-size:12px;font-weight:700}
                .addbar{display:flex;justify-content:flex-end;margin:10px 0}.plus{width:46px;height:46px;border-radius:50%;font-size:28px;line-height:1;padding:0;background:#126d5c;box-shadow:0 6px 16px #126d5c33}
                .modal{position:fixed;inset:0;background:#0b1724aa;display:none;align-items:center;justify-content:center;padding:14px;z-index:5}.modal.open{display:flex}.modalbox{background:#f4f6f8;border-radius:14px;width:min(760px,100%);max-height:92vh;overflow:auto;box-shadow:0 18px 50px #00000035}.modalhead{position:sticky;top:0;background:white;padding:14px 16px;border-bottom:1px solid #dde5ec;display:flex;align-items:center;justify-content:space-between;z-index:1}.modalhead h2{margin:0;font-size:20px}.modalbody{padding:12px 14px 16px}.formgrid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.field label{display:block;font-size:12px;color:#607080;font-weight:700;margin:0 0 4px}.modalactions{display:flex;gap:8px;justify-content:flex-end;margin-top:12px}.ghost{background:#e5ebf1;color:#17212b}
                input,select{min-width:0;width:100%;border:0;background:white;border-radius:7px;padding:9px 8px;font-size:14px;outline:1px solid transparent}input:focus,select:focus{outline:2px solid #8db8ff}
                button{border:0;border-radius:7px;padding:9px 10px;color:white;font-weight:700;background:#126d5c;white-space:nowrap}button.redbtn{background:#9a2b2b}button.bluebtn{background:#1f5d8f}
                table{width:100%;border-collapse:collapse;margin-top:8px;background:white;border-radius:9px;overflow:hidden}th,td{text-align:left;padding:8px;border-bottom:1px solid #e3e9f0;font-size:13px}th{background:#f6eefa;color:#4f3a70}td.actions{white-space:nowrap}
                @media(max-width:720px){.grid,.staffgrid{grid-template-columns:repeat(2,1fr)}.tool,.atttool,.formgrid,.holidaybar{grid-template-columns:1fr}.profile h2{font-size:15px}}
                </style></head><body><div class="top"><h1>Admin Dashboard</h1><small>Shared backend test</small></div><div class="wrap" id="app"></div><div id="modal" class="modal"></div>
                <script>
                let editing=false, rowsCache=[], openStaffId='';
                async function api(path,body){await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});editing=false;await load();if(openStaffId){const fresh=rowsCache.find(e=>e.id===openStaffId);if(fresh)openEmployeeModal(fresh);}}
                function val(id){return document.getElementById(id).value}
                function moneyAction(e,i){return `<div class="tool"><select id="act${i}" onfocus="editing=true"><option value="advance">Add advance</option><option value="repayment">Repayment</option><option value="salary">Pay salary</option><option value="wage">Update salary/day</option></select><input id="amt${i}" type="number" inputmode="decimal" placeholder="Amount" onfocus="editing=true" onblur="setTimeout(()=>editing=false,400)"><button onclick="runMoney('${e.id}',${i})">Apply</button></div>`}
                function runMoney(id,i){const a=val('act'+i), amount=val('amt'+i); const path=a==='advance'?'/api/advance':a==='repayment'?'/api/repayment':a==='salary'?'/api/salary-payment':'/api/daily-wage'; api(path,{staffId:id,amount})}
                function monthAttendance(e,i){return `<div class="card"><h3>Current month attendance</h3><div class="atttool"><input id="full${i}" type="number" inputmode="numeric" placeholder="Full days" onfocus="editing=true"><input id="half${i}" type="number" inputmode="numeric" placeholder="Half days" onfocus="editing=true"><input id="absent${i}" type="number" inputmode="numeric" placeholder="Absent days" onfocus="editing=true"><button onclick="api('/api/month-attendance',{staffId:'${e.id}',fullDays:val('full${i}'),halfDays:val('half${i}'),absentDays:val('absent${i}')})">Update</button></div></div>`}
                function closeModal(){editing=false;openStaffId='';document.getElementById('modal').classList.remove('open');document.getElementById('modal').innerHTML=''}
                function saveEmployee(id){api('/api/staff',{staffId:id||'',name:val('mName'),phone:val('mPhone'),emergencyContact:val('mEmergency'),dailyWage:val('mWage')||650,dob:val('mDob'),joiningDate:val('mJoin')});if(!id)closeModal()}
                function addButton(){return `<div class="addbar"><button class="plus" title="Add employee" onclick="openEmployeeModal()">+</button></div>`}
                function holidayBox(s){return `<div class="card"><div class="holidaybar"><div class="field"><label>Paid public holiday</label><input id="holidayDate" type="date" onfocus="editing=true"></div><button onclick="api('/api/holiday',{date:val('holidayDate')})">Add holiday</button></div><div class="chips">${(s.publicHolidays||[]).map(d=>`<span class="chip">${d}</span>`).join('')}</div></div>`}
                function field(id,label,type,value,placeholder){return `<div class="field"><label>${label}</label><input id="${id}" type="${type||'text'}" value="${value||''}" placeholder="${placeholder||''}" onfocus="editing=true"></div>`}
                function employeeForm(e){return `<div class="card"><div class="formgrid">${field('mName','Full name','text',e.name,'Full name')}${field('mPhone','Phone','tel',e.phone,'Phone number')}${field('mEmergency','Emergency contact','tel',e.emergencyContact,'Emergency contact')}${field('mWage','Salary/day','number',e.dailyWage||650,'650')}${field('mDob','Date of birth','date',e.dob,'')}${field('mJoin','Date of joining','date',e.joiningDate,'')}</div><div class="modalactions"><button class="ghost" onclick="closeModal()">Cancel</button><button onclick="saveEmployee('${e.id||''}')">Save</button></div></div>`}
                function openEmployeeModal(e){editing=true;e=e||{};const isNew=!e.id;openStaffId=isNew?'':e.id;document.getElementById('modal').innerHTML=`<div class="modalbox"><div class="modalhead"><h2>${isNew?'Add Employee':e.name}</h2><button class="ghost" onclick="closeModal()">Close</button></div><div class="modalbody">${employeeForm(e)}${isNew?'':employeeDetails(e,rowsCache.findIndex(r=>r.id===e.id))}</div></div>`;document.getElementById('modal').classList.add('open')}
                function avatar(e){return e.photo?`<img class="avatar" src="data:image/jpeg;base64,${e.photo}">`:`<div class="avatar">${e.name.split(' ').map(p=>p[0]).slice(0,2).join('')}</div>`}
                function leaveTable(e){return e.leaves.length?`<table><thead><tr><th>From</th><th>To</th><th>Reason</th><th>Status</th><th>Action</th></tr></thead><tbody>${e.leaves.map((l,idx)=>`<tr><td>${l.from}</td><td>${l.to}</td><td>${l.reason}</td><td>${l.status}</td><td class="actions">${l.status==='Pending'?`<button onclick="api('/api/leave-status',{staffId:'${e.id}',index:${idx},status:'Approved'})">Approve</button><button class="redbtn" onclick="api('/api/leave-status',{staffId:'${e.id}',index:${idx},status:'Rejected'})">Reject</button>`:'-'}</td></tr>`).join('')}</tbody></table>`:'<small>No leave requests</small>'}
                function salaryTable(e){return e.salaryLogs&&e.salaryLogs.length?`<table><thead><tr><th>Date</th><th>Paid salary</th></tr></thead><tbody>${e.salaryLogs.slice().reverse().map(r=>`<tr><td>${r.date}</td><td>Rs ${r.amount}</td></tr>`).join('')}</tbody></table>`:'<small>No salary payment rows</small>'}
                function advanceTable(e){return e.advanceLogs&&e.advanceLogs.length?`<table><thead><tr><th>Date</th><th>Action</th><th>Amount</th><th>Balance</th></tr></thead><tbody>${e.advanceLogs.slice().reverse().map(r=>`<tr><td>${r.date}</td><td>${r.type==='ADVANCE'?'Advance':'Repayment'}</td><td>Rs ${r.amount}</td><td>Rs ${r.balance}</td></tr>`).join('')}</tbody></table>`:'<small>No advance rows</small>'}
                function historyTable(e){return `<table><thead><tr><th>Month</th><th>Days</th><th>Earned</th><th>Paid</th><th>Leave days</th></tr></thead><tbody>${e.history.map(h=>`<tr><td>${h.month}</td><td>${h.days}</td><td>Rs ${h.earned}</td><td>Rs ${h.paid}</td><td>${h.leaves}</td></tr>`).join('')}</tbody></table>`}
                function summary(rows){const p=rows.filter(e=>e.today==='Full day'||e.today==='Paid holiday').length,h=rows.filter(e=>e.today==='Half day').length,a=rows.filter(e=>e.today==='Absent').length;return `<div class="summary"><div class="grid"><div class="card metric blue"><small>Staff</small><br><b>${rows.length}</b></div><div class="card metric green"><small>Present</small><br><b>${p}</b></div><div class="card metric blue"><small>Half day</small><br><b>${h}</b></div><div class="card metric red"><small>Absent</small><br><b>${a}</b></div></div></div>`}
                function statusBadge(e){const cls=e.today==='Full day'?'':e.today==='Half day'?'warn':'bad';return `<span class="badge ${cls}">${e.today}</span>`}
                function employeeDetails(e,i){return `<div class="grid"><div class="card metric green"><small>Month days</small><br><b>${e.monthDays}</b><br><small>F ${e.fullDays} H ${e.halfDays} A ${e.absentDays}</small></div><div class="card metric green"><small>After last paid</small><br><b>Rs ${e.remaining}</b></div><div class="card metric red"><small>Advance</small><br><b>Rs ${e.advance}</b></div><div class="card metric blue"><small>Last paid</small><br><b>Rs ${e.paid}</b><br><small>${e.lastSalaryDate||''}</small></div></div><div class="tools">${moneyAction(e,i)}</div>${monthAttendance(e,i)}<h3>Salary rows</h3>${salaryTable(e)}<h3>Advance ledger</h3>${advanceTable(e)}<h3>Leave</h3>${leaveTable(e)}<h3>History</h3>${historyTable(e)}`}
                function staffTile(e,i){const pending=e.leaves.some(l=>l.status==='Pending');return `<div class="card tile" onclick="openEmployeeModal(rowsCache[${i}])"><div class="profile">${avatar(e)}<div><h2>${e.name}</h2><small>${e.phone||'No phone'} • Rs ${e.dailyWage}/day</small><br>${statusBadge(e)} ${pending?'<span class="badge warn">Pending leave</span>':''}</div></div></div>`}
                async function load(){const s=await (await fetch('/api/state')).json();const rows=s.staff.filter(e=>e.role==='employee');rowsCache=rows;document.getElementById('app').innerHTML=summary(rows)+holidayBox(s)+addButton()+`<div class="staffgrid">${rows.map(staffTile).join('')}</div>`;} load(); setInterval(()=>{if(!editing)load();},3000);
                </script></body></html>
                """;
    }

    private Map<String, String> body(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        Map<String, String> map = new LinkedHashMap<>();
        if (raw.length() < 2) return map;
        String inner = raw.substring(1, raw.length() - 1);
        for (String pair : inner.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) map.put(clean(parts[0]), clean(parts[1]));
        }
        return map;
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

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (Exception exception) { return -1; }
    }

    private LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); } catch (Exception exception) { return null; }
    }

    private String firstName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) return "";
        return trimmed.split("\\s+")[0];
    }

    private String last10(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private boolean matchesLoginPhone(Staff employee, String phone) {
        if (phone.isBlank()) return false;
        if (last10(employee.phone).equals(phone)) return true;
        if (employee.loginPhones == null) return false;
        return employee.loginPhones.stream().anyMatch(alias -> last10(alias).equals(phone));
    }

    private List<String> splitLoginAliases(String aliases) {
        if (aliases == null || aliases.isBlank()) return new ArrayList<>();
        return Arrays.stream(aliases.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String loginAliasText(Staff employee) {
        if (employee.loginPhones == null) return "";
        return employee.loginPhones.stream()
                .map(this::last10)
                .filter(value -> !value.isBlank())
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
