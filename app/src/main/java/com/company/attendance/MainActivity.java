package com.company.attendance;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.text.InputType;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 11;
    private static final int PHOTO_REQUEST = 12;
    private static final int CAMERA_REQUEST = 13;
    private static final int PHONE_REQUEST = 14;
    private static final String BACKEND_URL = "http://192.168.29.23:8080";
    private static final LocalTime CUT_OFF = LocalTime.of(10, 30);
    private static final double SHOP_RADIUS_METERS = 100.0;
    private static final double DEFAULT_SHOP_LAT = 23.2599;
    private static final double DEFAULT_SHOP_LON = 77.4126;
    private static final int SHOP_LOCK_VERSION = 2;

    private final Map<String, Staff> staffById = new LinkedHashMap<>();
    private final Map<String, AttendanceRecord> attendance = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private LinearLayout content;
    private LinearLayout tabBar;
    private LinearLayout headerAvatar;
    private Staff selectedStaff;
    private String activeTab = "dashboard";
    private String pendingLoginPhone;
    private boolean pendingShopLocationSetup;
    private boolean syncInProgress;
    private boolean autoAttendanceInProgress;
    private boolean pendingFlushInProgress;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            syncFromBackend();
            tryAutoPresentAtShop();
            syncHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("attendance-app", MODE_PRIVATE);
        seedData();
        if (isLoggedIn()) {
            buildUi();
        } else {
            showLogin();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isLoggedIn() && selectedStaff != null) {
            syncFromBackend();
            uploadLocalPhotoIfAny();
            startAutoSync();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoSync();
    }

    private void seedData() {
        LocalDate today = LocalDate.now();

        Staff bhumika = new Staff("ADM001", "Bhumika Kumawat", 0);
        bhumika.nickname = "Bhumika";
        bhumika.dateOfBirth = LocalDate.of(1995, 1, 1);
        bhumika.phone = "8962569528";
        bhumika.dateOfJoining = LocalDate.of(2026, 6, 1);
        bhumika.emergencyContact = "Not added yet";

        staffById.put(bhumika.id, bhumika);
        addOperationStaff("OP002", "Shyam lal nishad");
        addOperationStaff("OP003", "Rahul Joshi");
        addOperationStaff("OP004", "Rahul Yadav");
        addOperationStaff("OP005", "Anshu");
        addOperationStaff("OP006", "Neha Sarang");
        addOperationStaff("OP007", "Annu");
        addOperationStaff("OP008", "Preeti Sahu");

    }

    private void addOperationStaff(String id, String name) {
        Staff staff = new Staff(id, name, 650);
        staff.nickname = name.split("\\s+")[0];
        staff.phone = "Not added";
        staff.dateOfBirth = LocalDate.of(1995, 1, 1);
        staff.dateOfJoining = LocalDate.of(2026, 6, 1);
        staff.emergencyContact = "Not added yet";
        staffById.put(staff.id, staff);
    }

    private void markSeed(Staff staff, LocalDate date) {
        attendance.put(key(staff.id, date), new AttendanceRecord(staff.id, date, "PRESENT", 1.0));
    }

    private boolean isLoggedIn() {
        String loggedPhone = prefs.getString("loggedPhone", "");
        String loggedStaffId = prefs.getString("loggedStaffId", "");
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String boundDevice = prefs.getString("loginDevice:" + loggedPhone, "");
        return !loggedStaffId.isEmpty() && !loggedPhone.isEmpty() && (boundDevice.isEmpty() || boundDevice.equals(deviceId));
    }

    private void showLogin() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.rgb(244, 246, 248));
        LinearLayout login = new LinearLayout(this);
        login.setOrientation(LinearLayout.VERTICAL);
        login.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        login.setPadding(dp(20), dp(80), dp(20), dp(24));
        scrollView.addView(login);
        setContentView(scrollView);

        TextView title = text("Staff Login", 26, true);
        title.setGravity(android.view.Gravity.CENTER);
        login.addView(title);
        TextView subtitle = text("Enter registered phone number", 14, false);
        subtitle.setGravity(android.view.Gravity.CENTER);
        subtitle.setTextColor(Color.rgb(94, 110, 126));
        login.addView(subtitle);

        EditText phoneInput = input("Phone number");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        login.addView(phoneInput);

        Button loginButton = primaryButton("Login", Color.rgb(18, 109, 92));
        loginButton.setOnClickListener(v -> loginWithPhone(phoneInput.getText().toString()));
        login.addView(loginButton);
    }

    private void loginWithPhone(String phone) {
        String normalized = normalizePhone(phone);
        if (normalized.length() != 10) {
            toast("Enter a valid 10 digit phone number");
            return;
        }

        if (!hasPhonePermission()) {
            pendingLoginPhone = normalized;
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE}, PHONE_REQUEST);
            return;
        }

        completePhoneLogin(normalized);
    }

    private void completePhoneLogin(String normalized) {
        List<String> simNumbers = simPhoneNumbers();
        if (!simNumbers.isEmpty() && !simNumbers.contains(normalized)) {
            toast("This SIM does not match " + normalized);
            return;
        }
        if (simNumbers.isEmpty()) {
            toast("SIM number not exposed by Android/carrier. Using device binding for test.");
        }

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String boundDevice = prefs.getString("loginDevice:" + normalized, "");
        if (!boundDevice.isEmpty() && !boundDevice.equals(deviceId)) {
            toast("This number is already linked to another phone");
            return;
        }

        loginFromBackend(normalized, deviceId);
    }

    private void loginFromBackend(String normalized, String deviceId) {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(BACKEND_URL + "/api/login").openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                String payload = "{\"phone\":\"" + normalized + "\"}";
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String response = stream == null ? "{}" : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (stream != null) stream.close();
                connection.disconnect();

                JSONObject json = new JSONObject(response);
                if (!json.optBoolean("ok", false)) {
                    String message = json.optString("message", "Phone not registered");
                    runOnUiThread(() -> toast(message));
                    return;
                }

                String staffId = json.optString("staffId", "");
                String role = json.optString("role", "employee");
                String name = json.optString("name", "Staff");
                prefs.edit()
                        .putString("loggedPhone", normalized)
                        .putString("loggedStaffId", staffId)
                        .putString("loggedRole", role)
                        .putString("loggedName", name)
                        .putString("loginDevice:" + normalized, deviceId)
                        .apply();
                runOnUiThread(this::buildUi);
            } catch (Exception exception) {
                runOnUiThread(() -> toast("Backend not reachable for login"));
            }
        }).start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 248));
        root.setPadding(dp(16), dp(12) + systemBarHeight("status_bar"), dp(16), dp(10));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = text("My Attendance", 24, true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        headerAvatar = new LinearLayout(this);
        headerAvatar.setGravity(android.view.Gravity.CENTER);
        headerAvatar.setOnClickListener(v -> {
            activeTab = "profile";
            render();
        });
        header.addView(headerAvatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        TextView subtitle = text("Operation staff salary and attendance", 13, false);
        subtitle.setTextColor(Color.rgb(94, 110, 126));
        root.addView(subtitle);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tabParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(tabBar, tabParams);

        ScrollView scrollView = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24) + systemBarHeight("navigation_bar"));
        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        String staffId = prefs.getString("loggedStaffId", "");
        selectedStaff = staffById.get(staffId);
        if (selectedStaff == null) {
            selectedStaff = new Staff(staffId.isEmpty() ? "OP003" : staffId, prefs.getString("loggedName", "Staff"), 0);
            staffById.put(selectedStaff.id, selectedStaff);
        }
        String loggedPhone = prefs.getString("loggedPhone", "");
        if (!loggedPhone.isEmpty() && selectedStaff.id.equals(staffId)) {
            selectedStaff.phone = loggedPhone;
        }
        render();
        uploadLocalPhotoIfAny();
        startAutoSync();
    }

    private void render() {
        renderHeaderAvatar();
        renderTabs();
        content.removeAllViews();

        if ("profile".equals(activeTab)) {
            renderProfile();
            return;
        }
        if ("admin".equals(activeTab)) {
            renderAdmin();
            return;
        }
        if ("leave".equals(activeTab)) {
            renderLeave();
            return;
        }
        if ("history".equals(activeTab)) {
            renderHistory();
            return;
        }
        renderDashboard();
        syncFromBackend();
    }

    private void renderDashboard() {
        LocalDate today = LocalDate.now();
        LocalDate salaryStart = selectedStaff.lastSalaryThroughDate == null
                ? today.withDayOfMonth(1)
                : selectedStaff.lastSalaryThroughDate.plusDays(1);
        double unpaidDays = attendanceDays(selectedStaff.id, salaryStart, today);
        double monthDays = selectedStaff.serverMonthDays >= 0 ? selectedStaff.serverMonthDays : attendanceDays(selectedStaff.id, today.withDayOfMonth(1), today);
        double monthSalary = selectedStaff.serverEarned >= 0 ? selectedStaff.serverEarned : monthDays * selectedStaff.dailyWage;
        double remainingSalary = selectedStaff.serverRemaining >= 0 ? selectedStaff.serverRemaining : Math.max(0, monthSalary - selectedStaff.lastSalaryAmount);
        String todayStatus = todayStatus(selectedStaff.id);
        if (today.getDayOfWeek() == DayOfWeek.SUNDAY && "NOT_MARKED".equals(todayStatus)) todayStatus = "SUNDAY_HOLIDAY";

        content.addView(metricCard(
                R.drawable.ic_attendance,
                Color.rgb(18, 109, 92),
                Color.rgb(232, 246, 242),
                "Today",
                new String[][]{
                        {"Salary / day", "Rs " + money(selectedStaff.dailyWage)},
                        {"Status", displayStatus(todayStatus)}
                }
        ));

        LinearLayout attendanceActions = new LinearLayout(this);
        attendanceActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.setMargins(0, dp(12), 0, 0);

        Button presentButton = new Button(this);
        presentButton.setText(LocalTime.now().isAfter(CUT_OFF) ? "Present - Half Day" : "Present - Full Day");
        presentButton.setAllCaps(false);
        presentButton.setTextColor(Color.WHITE);
        presentButton.setTextSize(16);
        presentButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        presentButton.setBackground(roundRect(Color.rgb(18, 109, 92), 8));
        presentButton.setOnClickListener(v -> markPresent());

        Button absentButton = new Button(this);
        absentButton.setText("Absent");
        absentButton.setAllCaps(false);
        absentButton.setTextColor(Color.WHITE);
        absentButton.setTextSize(16);
        absentButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        absentButton.setBackground(roundRect(Color.rgb(154, 43, 43), 8));
        absentButton.setOnClickListener(v -> markAbsent());

        attendanceActions.addView(presentButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        attendanceActions.addView(new TextView(this), new LinearLayout.LayoutParams(dp(8), 1));
        attendanceActions.addView(absentButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        attendanceActions.setVisibility("NOT_MARKED".equals(todayStatus) ? View.VISIBLE : View.GONE);
        content.addView(attendanceActions, actionsParams);

        content.addView(metricCard(
                R.drawable.ic_salary,
                Color.rgb(18, 109, 92),
                Color.rgb(232, 246, 242),
                "Salary",
                new String[][]{
                        {"Remaining", "Rs " + money(remainingSalary)},
                        {"This month", money(monthDays) + " days"},
                        {"Earned", "Rs " + money(monthSalary)},
                        {"Paid", "Rs " + money(selectedStaff.lastSalaryAmount)}
                }
        ));

        content.addView(metricCard(
                R.drawable.ic_advance,
                Color.rgb(154, 43, 43),
                Color.rgb(255, 235, 235),
                "Advance",
                new String[][]{
                        {"Owed", "Rs " + money(selectedStaff.advanceBalance)},
                        {"Weekly payback", "Rs " + money(weeklyRepayment(today, selectedStaff))},
                        {"Next", nextRepaymentDate(today, selectedStaff)}
                }
        ));
    }

    private void renderProfile() {
        content.addView(profileCard());
        Button shopButton = primaryButton("Set Current Location as Shop", Color.rgb(18, 109, 92));
        shopButton.setOnClickListener(v -> setCurrentLocationAsShop());
        content.addView(shopButton);
        Button photoButton = primaryButton("Add / Edit Profile Photo", Color.rgb(47, 111, 115));
        photoButton.setOnClickListener(v -> pickProfilePhoto());
        content.addView(photoButton);
        Button cameraButton = primaryButton("Take Photo", Color.rgb(31, 93, 143));
        cameraButton.setOnClickListener(v -> takeProfilePhoto());
        content.addView(cameraButton);
    }

    private void renderLeave() {
        content.addView(iconCard(
                R.drawable.ic_leave,
                Color.rgb(122, 77, 154),
                Color.rgb(246, 238, 250),
                "Apply Leave",
                "Submit leave request for admin approval."
        ));

        EditText fromDate = dateInput("From date");
        EditText toDate = dateInput("To date");
        Spinner reason = reasonDropdown();
        content.addView(fromDate);
        content.addView(toDate);
        content.addView(reason);

        Button applyButton = primaryButton("Apply Leave", Color.rgb(122, 77, 154));
        applyButton.setOnClickListener(v -> applyLeave(fromDate.getText().toString(), toDate.getText().toString(), reason.getSelectedItem().toString()));
        content.addView(applyButton);

        content.addView(iconCard(
                R.drawable.ic_leave,
                Color.rgb(90, 74, 143),
                Color.rgb(239, 235, 249),
                "My Leave Requests",
                selectedStaff.leaves.isEmpty() ? "No leave requests yet." : "Latest status below."
        ));
        content.addView(leaveTable(selectedStaff, false));
    }

    private void renderHistory() {
        content.addView(iconCard(
                R.drawable.ic_salary,
                Color.rgb(31, 93, 143),
                Color.rgb(232, 241, 249),
                "History",
                "Month wise attendance, salary, and leave."
        ));

        if (selectedStaff.history.isEmpty()) {
            content.addView(iconCard(
                    R.drawable.ic_salary,
                    Color.rgb(94, 110, 126),
                    Color.rgb(244, 247, 250),
                    "No Data",
                    "History will appear after sync."
            ));
            return;
        }

        for (MonthlyHistory month : selectedStaff.history) {
            content.addView(historyRow(month));
        }
    }

    private LinearLayout historyRow(MonthlyHistory month) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(roundRect(Color.WHITE, 8));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(params);
        card.setOnClickListener(v -> showMonthCalendar(month));

        TextView title = text(month.month, 15, true);
        title.setTextColor(Color.rgb(31, 93, 143));
        card.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(compactMetric("Days", money(month.days)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(compactMetric("Earned", "Rs " + money(month.earned)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(compactMetric("Paid", "Rs " + money(month.paid)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(compactMetric("Leaves", String.valueOf(month.leaves)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row);
        return card;
    }

    private void showMonthCalendar(MonthlyHistory month) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month.month);
        } catch (Exception exception) {
            toast("Month details not available");
            return;
        }
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(14), dp(8), dp(14), 0);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[] heads = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        for (String head : heads) header.addView(calendarHeader(head), new LinearLayout.LayoutParams(0, dp(28), 1));
        grid.addView(header);

        LocalDate cursor = yearMonth.atDay(1);
        int leading = cursor.getDayOfWeek().getValue() - 1;
        int daysInMonth = yearMonth.lengthOfMonth();
        int cell = 0;
        LinearLayout row = calendarRow();
        for (int i = 0; i < leading; i++) {
            row.addView(new TextView(this), new LinearLayout.LayoutParams(0, dp(62), 1));
            cell++;
        }
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = yearMonth.atDay(day);
            row.addView(calendarCell(date), new LinearLayout.LayoutParams(0, dp(62), 1));
            cell++;
            if (cell == 7) {
                grid.addView(row);
                row = calendarRow();
                cell = 0;
            }
        }
        if (cell > 0) {
            while (cell < 7) {
                row.addView(new TextView(this), new LinearLayout.LayoutParams(0, dp(62), 1));
                cell++;
            }
            grid.addView(row);
        }
        wrapper.addView(grid);
        new AlertDialog.Builder(this)
                .setTitle(month.month)
                .setView(wrapper)
                .setPositiveButton("Close", null)
                .show();
    }

    private LinearLayout calendarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView calendarHeader(String label) {
        TextView view = text(label, 11, true);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(Color.rgb(94, 110, 126));
        return view;
    }

    private TextView calendarCell(LocalDate date) {
        AttendanceRecord record = attendance.get(key(selectedStaff.id, date));
        String status = record == null ? "" : displayStatus(record.status);
        String shortStatus = record == null ? "-" : shortStatus(record.status);
        TextView view = text(date.getDayOfMonth() + "\n" + shortStatus, 11, true);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(record == null ? Color.rgb(94, 110, 126) : statusColor(record.status));
        view.setBackground(roundRect(record == null ? Color.rgb(244, 247, 250) : lightStatusColor(record.status), 8));
        view.setPadding(dp(2), dp(4), dp(2), dp(4));
        return view;
    }

    private LinearLayout compactMetric(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), 0, dp(4), 0);
        TextView labelView = text(label, 10, true);
        labelView.setTextColor(Color.rgb(94, 110, 126));
        TextView valueView = text(value, 12, true);
        valueView.setTextColor(Color.rgb(39, 51, 64));
        box.addView(labelView);
        box.addView(valueView);
        return box;
    }

    private void renderAdmin() {
        LocalDate today = LocalDate.now();
        int presentCount = 0;
        int absentCount = 0;
        int halfDayCount = 0;
        for (Staff staff : staffById.values()) {
            String status = todayStatus(staff.id);
            if ("PRESENT".equals(status)) {
                presentCount++;
            } else if ("HALF_DAY".equals(status)) {
                halfDayCount++;
            } else {
                absentCount++;
            }
        }

        content.addView(metricCard(
                R.drawable.ic_security,
                Color.rgb(31, 93, 143),
                Color.rgb(232, 241, 249),
                "Admin",
                new String[][]{
                        {"Staff", String.valueOf(staffById.size())},
                        {"Present", String.valueOf(presentCount)},
                        {"Half day", String.valueOf(halfDayCount)},
                        {"Absent", String.valueOf(absentCount)}
                }
        ));

        renderPendingLeaveTasks();

        for (Staff staff : staffById.values()) {
            content.addView(adminStaffCard(staff, today));
        }
    }

    private void renderPendingLeaveTasks() {
        boolean hasPending = false;
        for (Staff staff : staffById.values()) {
            for (int i = 0; i < staff.leaves.size(); i++) {
                LeaveRequest request = staff.leaves.get(i);
                if ("Pending".equals(request.status)) {
                    if (!hasPending) {
                        content.addView(iconCard(
                                R.drawable.ic_leave,
                                Color.rgb(122, 77, 154),
                                Color.rgb(246, 238, 250),
                                "Pending Tasks",
                                "Approve or reject employee leave requests."
                        ));
                    }
                    hasPending = true;
                    content.addView(leaveTaskCard(staff, i, request));
                }
            }
        }
        if (!hasPending) {
            content.addView(iconCard(
                    R.drawable.ic_leave,
                    Color.rgb(122, 77, 154),
                    Color.rgb(246, 238, 250),
                    "Pending Tasks",
                    "No pending leave requests."
            ));
        }
    }

    private LinearLayout leaveTaskCard(Staff staff, int index, LeaveRequest request) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(Color.WHITE, 10));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(params);

        TextView title = text(staff.name, 16, true);
        title.setTextColor(Color.rgb(39, 51, 64));
        card.addView(title);
        card.addView(leaveRow(request, true));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button approve = smallActionButton("Approve", Color.rgb(18, 109, 92));
        approve.setOnClickListener(v -> updateLeaveStatus(staff, index, "Approved"));
        Button reject = smallActionButton("Reject", Color.rgb(154, 43, 43));
        reject.setOnClickListener(v -> updateLeaveStatus(staff, index, "Rejected"));
        actions.addView(approve, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(new TextView(this), new LinearLayout.LayoutParams(dp(8), 1));
        actions.addView(reject, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(10), 0, 0);
        card.addView(actions, actionParams);
        return card;
    }

    private void updateLeaveStatus(Staff staff, int index, String status) {
        if (index >= 0 && index < staff.leaves.size()) {
            staff.leaves.get(index).status = status;
        }
        postBackend("/api/leave-status", "{\"staffId\":\"" + staff.id + "\",\"index\":\"" + index + "\",\"status\":\"" + status + "\"}");
        toast("Leave " + status.toLowerCase());
        render();
    }

    private LinearLayout adminStaffCard(Staff staff, LocalDate today) {
        double monthDays = attendanceDays(staff.id, today.withDayOfMonth(1), today);
        if (staff.serverMonthDays >= 0) monthDays = staff.serverMonthDays;
        double monthSalary = staff.serverEarned >= 0 ? staff.serverEarned : monthDays * staff.dailyWage;
        double remainingSalary = staff.serverRemaining >= 0 ? staff.serverRemaining : Math.max(0, monthSalary - staff.lastSalaryAmount);
        String leaveText = staff.leaves.isEmpty() ? "No requests" : staff.leaves.size() + " request";
        if (staff.leaves.size() != 1) leaveText += "s";

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(Color.WHITE, 10));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(params);
        card.setOnClickListener(v -> {
            selectedStaff = staff;
            activeTab = "profile";
            render();
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.addView(employeeAvatar(staff, dp(54)), new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView name = text(staff.name, 17, true);
        name.setTextColor(Color.rgb(39, 51, 64));
        TextView status = text(displayStatus(todayStatus(staff.id)), 14, true);
        status.setTextColor(statusColor(todayStatus(staff.id)));
        info.addView(name);
        info.addView(status);
        top.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);

        LinearLayout rowOne = new LinearLayout(this);
        rowOne.setOrientation(LinearLayout.HORIZONTAL);
        rowOne.addView(metricBox("Days", money(monthDays), Color.rgb(18, 109, 92)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        rowOne.addView(new TextView(this), new LinearLayout.LayoutParams(dp(8), 1));
        rowOne.addView(metricBox("Salary", "Rs " + money(remainingSalary), Color.rgb(18, 109, 92)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(10), 0, 0);
        card.addView(rowOne, rowParams);

        LinearLayout rowTwo = new LinearLayout(this);
        rowTwo.setOrientation(LinearLayout.HORIZONTAL);
        rowTwo.addView(metricBox("Advance", "Rs " + money(staff.advanceBalance), Color.rgb(154, 43, 43)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        rowTwo.addView(new TextView(this), new LinearLayout.LayoutParams(dp(8), 1));
        rowTwo.addView(metricBox("Leave", leaveText, Color.rgb(122, 77, 154)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(rowTwo, rowParams);

        if (!staff.leaves.isEmpty()) {
            card.addView(leaveTable(staff, true));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button addAdvance = smallActionButton("+ Advance", Color.rgb(154, 43, 43));
        addAdvance.setOnClickListener(v -> {
            v.setPressed(false);
            showAmountDialog("Add Advance", "Amount", amount -> {
                staff.advanceBalance += amount;
                toast("Advance added");
                render();
            });
        });
        Button repayment = smallActionButton("Repayment", Color.rgb(18, 109, 92));
        repayment.setOnClickListener(v -> {
            v.setPressed(false);
            showAmountDialog("Apply Repayment", "Amount", amount -> {
                staff.advanceBalance = Math.max(0, staff.advanceBalance - amount);
                staff.lastAdvanceReturnDate = LocalDate.now();
                toast("Repayment applied");
                render();
            });
        });
        actions.addView(addAdvance, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(new TextView(this), new LinearLayout.LayoutParams(dp(8), 1));
        actions.addView(repayment, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(10), 0, 0);
        card.addView(actions, actionParams);
        return card;
    }

    private void markPresent() {
        if (LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY) {
            toast("Sunday is weekly holiday and is not counted as salary day.");
            return;
        }
        if (hasApprovedLeaveToday(selectedStaff)) {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            saveAttendance(deviceId, "ABSENT", 0, true);
            toast("Approved leave today. Attendance marked absent.");
            return;
        }
        if (!hasLocationPermission()) {
            pendingShopLocationSetup = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!canUseDevice(deviceId)) return;

        getCurrentLocation(location -> processAttendance(deviceId, location));
    }

    private void markAbsent() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!canUseDevice(deviceId)) return;
        saveAttendance(deviceId, "ABSENT", 0, true);
    }

    private boolean canUseDevice(String deviceId) {
        String lockedStaffId = prefs.getString("staffByDevice:" + deviceId, null);
        if (lockedStaffId != null && !lockedStaffId.equals(selectedStaff.id)) {
            Staff lockedStaff = staffById.get(lockedStaffId);
            toast("This phone is already linked to " + (lockedStaff == null ? lockedStaffId : lockedStaff.name));
            return false;
        }

        String lockedDeviceId = prefs.getString("deviceByStaff:" + selectedStaff.id, null);
        if (lockedDeviceId != null && !lockedDeviceId.equals(deviceId)) {
            toast(selectedStaff.name + " is already linked to another phone");
            return false;
        }
        return true;
    }

    private void renderTabs() {
        tabBar.removeAllViews();
        tabBar.addView(tabItem(R.drawable.ic_dashboard, "Home", "dashboard"), new LinearLayout.LayoutParams(0, dp(58), 1));
        tabBar.addView(tabGap(), new LinearLayout.LayoutParams(dp(6), dp(46)));
        tabBar.addView(tabItem(R.drawable.ic_leave, "Leave", "leave"), new LinearLayout.LayoutParams(0, dp(58), 1));
        tabBar.addView(tabGap(), new LinearLayout.LayoutParams(dp(6), dp(46)));
        tabBar.addView(tabItem(R.drawable.ic_salary, "History", "history"), new LinearLayout.LayoutParams(0, dp(58), 1));
        if (isAdmin()) {
            tabBar.addView(tabGap(), new LinearLayout.LayoutParams(dp(6), dp(46)));
            tabBar.addView(tabItem(R.drawable.ic_admin, "Admin", "admin"), new LinearLayout.LayoutParams(0, dp(58), 1));
        } else if ("admin".equals(activeTab)) {
            activeTab = "dashboard";
        }
    }

    private TextView tabGap() {
        return new TextView(this);
    }

    private void renderHeaderAvatar() {
        headerAvatar.removeAllViews();
        Bitmap cameraBitmap = bitmapFromBase64(prefs.getString("photoBitmap:" + selectedStaff.id, ""));
        if (cameraBitmap != null) {
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(cameraBitmap);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackground(roundRect(Color.WHITE, 24));
            headerAvatar.addView(imageView, new LinearLayout.LayoutParams(dp(48), dp(48)));
            return;
        }

        String photoUri = prefs.getString("photo:" + selectedStaff.id, "");
        if (!photoUri.isEmpty()) {
            ImageView imageView = new ImageView(this);
            imageView.setImageURI(Uri.parse(photoUri));
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackground(roundRect(Color.WHITE, 24));
            headerAvatar.addView(imageView, new LinearLayout.LayoutParams(dp(48), dp(48)));
            return;
        }

        TextView initials = text(initials(selectedStaff.name), 16, true);
        initials.setGravity(android.view.Gravity.CENTER);
        initials.setTextColor(Color.WHITE);
        initials.setBackground(roundRect(Color.rgb(47, 111, 115), 24));
        headerAvatar.addView(initials, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private LinearLayout tabItem(int iconRes, String label, String tab) {
        boolean selected = activeTab.equals(tab) || ("profile".equals(activeTab) && "dashboard".equals(tab));
        int accent = selected ? Color.WHITE : Color.rgb(18, 109, 92);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER);
        item.setPadding(dp(6), dp(6), dp(6), dp(5));
        item.setBackground(roundRect(selected ? Color.rgb(18, 109, 92) : Color.WHITE, 12));
        item.setElevation(selected ? dp(3) : dp(1));
        item.setOnClickListener(v -> {
            activeTab = tab;
            render();
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(accent);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView text = text(label, 11, true);
        text.setTextColor(accent);
        text.setPadding(0, dp(2), 0, 0);
        item.addView(text);
        return item;
    }

    private LinearLayout profileCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(Color.rgb(229, 244, 245), 10));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(params);

        Bitmap cameraBitmap = bitmapFromBase64(prefs.getString("photoBitmap:" + selectedStaff.id, ""));
        String photoUri = prefs.getString("photo:" + selectedStaff.id, "");
        if (cameraBitmap != null) {
            card.addView(employeeAvatar(selectedStaff, dp(72)), new LinearLayout.LayoutParams(dp(72), dp(72)));
        } else if (photoUri.isEmpty()) {
            card.addView(employeeAvatar(selectedStaff, dp(72)), new LinearLayout.LayoutParams(dp(72), dp(72)));
        } else {
            card.addView(employeeAvatar(selectedStaff, dp(72)), new LinearLayout.LayoutParams(dp(72), dp(72)));
        }

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        TextView name = text(selectedStaff.name, 18, true);
        name.setTextColor(Color.rgb(47, 111, 115));
        details.addView(name);
        details.addView(profileRow("Nickname", selectedStaff.nickname));
        details.addView(profileDateOfBirthRow());
        details.addView(profileRow("Phone", selectedStaff.phone));
        details.addView(profileRow("Joining date", selectedStaff.dateOfJoining.toString()));
        details.addView(profileRow("Emergency", selectedStaff.emergencyContact));
        card.addView(details, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return card;
    }

    private LinearLayout profileDateOfBirthRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, 0);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text("Date of birth", 12, true);
        labelView.setTextColor(Color.rgb(94, 110, 126));
        TextView valueView = text(selectedStaff.dateOfBirth.toString(), 15, false);
        valueView.setTextColor(Color.rgb(39, 51, 64));
        textBlock.addView(labelView);
        textBlock.addView(valueView);
        row.addView(textBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button edit = primaryButton("Edit", Color.rgb(47, 111, 115));
        edit.setTextSize(12);
        edit.setPadding(dp(8), 0, dp(8), 0);
        edit.setOnClickListener(v -> showBirthDatePicker());
        row.addView(edit, new LinearLayout.LayoutParams(dp(76), dp(42)));
        return row;
    }

    private LinearLayout profileRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(9), 0, 0);
        TextView labelView = text(label, 12, true);
        labelView.setTextColor(Color.rgb(94, 110, 126));
        TextView valueView = text(value, 15, false);
        valueView.setTextColor(Color.rgb(39, 51, 64));
        row.addView(labelView);
        row.addView(valueView);
        return row;
    }

    private void applyLeave(String from, String to, String reason) {
        if (from.trim().isEmpty() || to.trim().isEmpty()) {
            toast("Select from and to date");
            return;
        }
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = LocalDate.parse(from.trim());
            toDate = LocalDate.parse(to.trim());
        } catch (Exception exception) {
            toast("Select valid dates");
            return;
        }
        if (fromDate.isBefore(LocalDate.now())) {
            toast("Past dates are not allowed");
            return;
        }
        if (toDate.isBefore(fromDate)) {
            toast("To date cannot be before from date");
            return;
        }
        if (hasOverlappingLeave(selectedStaff, fromDate, toDate)) {
            toast("Leave already applied for selected dates");
            return;
        }
        String selectedReason = reason.trim().isEmpty() ? "Personal reason" : reason.trim();
        selectedStaff.leaves.add(new LeaveRequest(from.trim(), to.trim(), selectedReason, "Pending"));
        postBackend("/api/leave", "{\"staffId\":\"" + selectedStaff.id + "\",\"fromDate\":\"" + escape(from.trim())
                + "\",\"toDate\":\"" + escape(to.trim()) + "\",\"reason\":\"" + escape(selectedReason) + "\"}");
        toast("Leave request submitted");
        render();
    }

    private void pickProfilePhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PHOTO_REQUEST);
    }

    private void takeProfilePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            toast("No camera app found");
            return;
        }
        startActivityForResult(intent, CAMERA_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAMERA_REQUEST && resultCode == RESULT_OK && data != null) {
            Bitmap bitmap = (Bitmap) data.getExtras().get("data");
            if (bitmap != null) {
                String encoded = bitmapToBase64(bitmap);
                prefs.edit()
                        .putString("photoBitmap:" + selectedStaff.id, encoded)
                        .remove("photo:" + selectedStaff.id)
                        .apply();
                postProfilePhoto(encoded);
                toast("Profile photo updated");
                render();
            }
            return;
        }

        if (requestCode != PHOTO_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            String encoded = bitmapToBase64(bitmap);
            prefs.edit()
                    .putString("photoBitmap:" + selectedStaff.id, encoded)
                    .remove("photo:" + selectedStaff.id)
                    .apply();
            postProfilePhoto(encoded);
            toast("Profile photo updated");
            render();
        } catch (Exception exception) {
            toast("Could not update profile photo");
        }
    }

    private String bitmapToBase64(Bitmap bitmap) {
        Bitmap normalized = scaleBitmap(bitmap, 420);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        normalized.compress(Bitmap.CompressFormat.JPEG, 85, output);
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap scaleBitmap(Bitmap bitmap, int maxSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxSize && height <= maxSize) {
            return bitmap;
        }
        float ratio = Math.min((float) maxSize / width, (float) maxSize / height);
        int targetWidth = Math.max(1, Math.round(width * ratio));
        int targetHeight = Math.max(1, Math.round(height * ratio));
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private Bitmap bitmapFromBase64(String value) {
        if (value.isEmpty()) return null;
        byte[] bytes = Base64.decode(value, Base64.DEFAULT);
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private String leaveHistoryText(String staffId) {
        Staff staff = staffById.get(staffId);
        if (staff == null || staff.leaves.isEmpty()) return "No leave requests yet.";
        StringBuilder builder = new StringBuilder();
        for (LeaveRequest leave : staff.leaves) {
            builder.append(leave.from).append(" to ").append(leave.to).append(" | ")
                    .append(leave.status).append(" | ").append(leave.reason).append("\n");
        }
        return builder.toString().trim();
    }

    private LinearLayout leaveTable(Staff staff, boolean adminActions) {
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setPadding(dp(10), dp(10), dp(10), dp(10));
        table.setBackground(roundRect(Color.WHITE, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        table.setLayoutParams(params);
        table.addView(leaveHeader(adminActions));
        if (staff.leaves.isEmpty()) {
            TextView empty = text("No leave requests", 14, false);
            empty.setTextColor(Color.rgb(94, 110, 126));
            empty.setPadding(0, dp(10), 0, 0);
            table.addView(empty);
            return table;
        }
        for (int i = 0; i < staff.leaves.size(); i++) {
            table.addView(leaveRow(staff.leaves.get(i), adminActions));
            if (adminActions && "Pending".equals(staff.leaves.get(i).status)) {
                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                Button approve = smallActionButton("Approve", Color.rgb(18, 109, 92));
                int index = i;
                approve.setOnClickListener(v -> updateLeaveStatus(staff, index, "Approved"));
                Button reject = smallActionButton("Reject", Color.rgb(154, 43, 43));
                reject.setOnClickListener(v -> updateLeaveStatus(staff, index, "Rejected"));
                actions.addView(approve, new LinearLayout.LayoutParams(0, dp(42), 1));
                actions.addView(new TextView(this), new LinearLayout.LayoutParams(dp(8), 1));
                actions.addView(reject, new LinearLayout.LayoutParams(0, dp(42), 1));
                table.addView(actions);
            }
        }
        return table;
    }

    private LinearLayout leaveHeader(boolean adminActions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(6));
        row.addView(tableCell("From", true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tableCell("To", true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tableCell("Reason", true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        row.addView(tableCell("Status", true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private LinearLayout leaveRow(LeaveRequest leave, boolean adminActions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(tableCell(leave.from, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tableCell(leave.to, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tableCell(leave.reason, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        TextView status = tableCell(leave.status, true);
        status.setTextColor(statusTextColor(leave.status));
        row.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private TextView tableCell(String value, boolean bold) {
        TextView cell = text(value, 12, bold);
        cell.setTextColor(bold ? Color.rgb(55, 65, 81) : Color.rgb(74, 85, 99));
        cell.setPadding(dp(4), 0, dp(4), 0);
        return cell;
    }

    private int statusTextColor(String status) {
        if ("Approved".equals(status)) return Color.rgb(18, 109, 92);
        if ("Rejected".equals(status)) return Color.rgb(154, 43, 43);
        return Color.rgb(138, 75, 18);
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextSize(15);
        editText.setSingleLine(false);
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        editText.setBackground(roundRect(Color.WHITE, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, dp(10), 0, 0);
        editText.setLayoutParams(params);
        return editText;
    }

    private EditText dateInput(String hint) {
        EditText editText = input(hint);
        editText.setInputType(InputType.TYPE_NULL);
        editText.setFocusable(false);
        editText.setOnClickListener(v -> showDatePicker(editText));
        return editText;
    }

    private void showDatePicker(EditText target) {
        LocalDate today = LocalDate.now();
        DatePicker picker = new DatePicker(this);
        picker.setMinDate(today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        final AlertDialog[] dialog = new AlertDialog[1];
        dialog[0] = new AlertDialog.Builder(this)
                .setView(picker)
                .create();
        picker.init(today.getYear(), today.getMonthValue() - 1, today.getDayOfMonth(), (view, year, monthOfYear, dayOfMonth) -> {
            target.setText(LocalDate.of(year, monthOfYear + 1, dayOfMonth).toString());
            dialog[0].dismiss();
        });
        dialog[0].show();
    }

    private void showBirthDatePicker() {
        LocalDate current = selectedStaff.dateOfBirth == null ? LocalDate.of(1995, 1, 1) : selectedStaff.dateOfBirth;
        DatePicker picker = new DatePicker(this);
        picker.setMaxDate(LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        final AlertDialog[] dialog = new AlertDialog[1];
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle("Date of birth")
                .setView(picker)
                .create();
        picker.init(current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth(), (view, year, monthOfYear, dayOfMonth) -> {
            LocalDate updated = LocalDate.of(year, monthOfYear + 1, dayOfMonth);
            selectedStaff.dateOfBirth = updated;
            postBackend("/api/staff", "{\"staffId\":\"" + selectedStaff.id + "\",\"dob\":\"" + updated + "\"}");
            toast("Date of birth updated");
            dialog[0].dismiss();
            render();
        });
        dialog[0].show();
    }

    private Spinner reasonDropdown() {
        Spinner spinner = new Spinner(this);
        String[] reasons = {"Personal reason", "Family emergency", "Not feeling well"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, reasons);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(roundRect(Color.WHITE, 8));
        spinner.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, dp(10), 0, 0);
        spinner.setLayoutParams(params);
        return spinner;
    }

    private Button primaryButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(roundRect(color, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, dp(12), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button smallActionButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(roundRect(color, 8));
        return button;
    }

    private void showAmountDialog(String title, String hint, AmountCallback callback) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(roundRect(Color.rgb(244, 246, 248), 8));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(18), dp(8), dp(18), 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        double amount = Double.parseDouble(input.getText().toString());
                        if (amount <= 0) {
                            toast("Amount must be above 0");
                            return;
                        }
                        callback.onAmount(amount);
                    } catch (NumberFormatException exception) {
                        toast("Enter valid amount");
                    }
                })
                .show();
    }

    private boolean hasApprovedLeaveToday(Staff staff) {
        LocalDate today = LocalDate.now();
        for (LeaveRequest leave : staff.leaves) {
            if (!"Approved".equals(leave.status)) continue;
            try {
                LocalDate from = LocalDate.parse(leave.from);
                LocalDate to = LocalDate.parse(leave.to);
                if (!today.isBefore(from) && !today.isAfter(to)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean hasOverlappingLeave(Staff staff, LocalDate fromDate, LocalDate toDate) {
        for (LeaveRequest leave : staff.leaves) {
            if ("Rejected".equals(leave.status)) continue;
            try {
                LocalDate existingFrom = LocalDate.parse(leave.from);
                LocalDate existingTo = LocalDate.parse(leave.to);
                if (!toDate.isBefore(existingFrom) && !fromDate.isAfter(existingTo)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void processAttendance(String deviceId, Location current) {
        Location shop = shopLocation();
        String currentWifi = currentWifiSsid();
        String shopWifi = prefs.getString("shopWifi", "");
        boolean wifiMatches = !shopWifi.isEmpty() && shopWifi.equals(currentWifi);
        if (current == null && !wifiMatches) {
            toast("Could not read GPS or shop Wi-Fi. Stand near shop and try again.");
            return;
        }
        if (current == null) {
            saveTimedPresent(deviceId);
            return;
        }
        float distance = shop.distanceTo(current);
        if (!wifiMatches && distance > SHOP_RADIUS_METERS) {
            toast("Attendance allowed only at shop. GPS distance: " + Math.round(distance) + "m");
            return;
        }

        saveTimedPresent(deviceId);
    }

    private void setCurrentLocationAsShop() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        getCurrentLocation(location -> {
            if (location == null) {
                toast("Could not read GPS. Keep location on and try again.");
                return;
            }
            String currentWifi = currentWifiSsid();
            prefs.edit()
                    .putFloat("shopLat", (float) location.getLatitude())
                    .putFloat("shopLon", (float) location.getLongitude())
                    .putString("shopWifi", currentWifi)
                    .putInt("shopLockVersion", SHOP_LOCK_VERSION)
                    .apply();
            toast("Shop location saved. Try marking present now.");
        });
    }

    private void saveTimedPresent(String deviceId) {
        double dayValue = LocalTime.now().isAfter(CUT_OFF) ? 0.5 : 1.0;
        String status = dayValue == 1.0 ? "PRESENT" : "HALF_DAY";
        saveAttendance(deviceId, status, dayValue, true);
    }

    private void saveAttendance(String deviceId, String status, double dayValue, boolean showToast) {
        attendance.put(key(selectedStaff.id, LocalDate.now()), new AttendanceRecord(selectedStaff.id, LocalDate.now(), status, dayValue));
        postBackend("/api/attendance", "{\"staffId\":\"" + selectedStaff.id + "\",\"status\":\"" + backendStatus(status)
                + "\",\"dayValue\":\"" + dayValue + "\"}");
        prefs.edit()
                .putString("staffByDevice:" + deviceId, selectedStaff.id)
                .putString("deviceByStaff:" + selectedStaff.id, deviceId)
                .apply();
        if (showToast) toast(selectedStaff.name + " marked " + displayStatus(status).toLowerCase());
        render();
    }

    private void postProfilePhoto(String encoded) {
        postBackend("/api/photo", "{\"staffId\":\"" + selectedStaff.id + "\",\"photo\":\"" + encoded + "\"}");
    }

    private void uploadLocalPhotoIfAny() {
        if (selectedStaff == null || isAdmin()) return;
        String encoded = prefs.getString("photoBitmap:" + selectedStaff.id, "");
        if (!encoded.isEmpty()) {
            postProfilePhoto(encoded);
        }
    }

    private void tryAutoPresentAtShop() {
        if (autoAttendanceInProgress || selectedStaff == null || isAdmin()) return;
        if (hasApprovedLeaveToday(selectedStaff)) return;
        if (!"NOT_MARKED".equals(todayStatus(selectedStaff.id)) || !hasLocationPermission()) return;
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!canUseDevice(deviceId)) return;
        autoAttendanceInProgress = true;
        getCurrentLocation(location -> {
            autoAttendanceInProgress = false;
            Location shop = shopLocation();
            String currentWifi = currentWifiSsid();
            String shopWifi = prefs.getString("shopWifi", "");
            boolean wifiMatches = !shopWifi.isEmpty() && shopWifi.equals(currentWifi);
            if (location == null && !wifiMatches) return;
            if (shop == null && location != null) {
                processAttendance(deviceId, location);
                return;
            }
            if (wifiMatches || (shop != null && location != null && shop.distanceTo(location) <= SHOP_RADIUS_METERS)) {
                saveTimedPresent(deviceId);
            }
        });
    }

    private void postBackend(String path, String json) {
        new Thread(() -> {
            try {
                sendBackendPost(path, json);
                runOnUiThread(this::syncFromBackend);
            } catch (Exception ignored) {
                queuePendingPost(path, json);
                runOnUiThread(() -> toast("Saved locally. Will sync when backend is reachable."));
            }
        }).start();
    }

    private void sendBackendPost(String path, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BACKEND_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream != null) stream.close();
        connection.disconnect();
        if (code >= 400) throw new IOException("Backend rejected " + path + " with " + code);
    }

    private void queuePendingPost(String path, String json) {
        try {
            JSONArray queue = pendingPostQueue();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject item = queue.getJSONObject(i);
                if (path.equals(item.optString("path")) && json.equals(item.optString("body"))) return;
            }
            JSONObject item = new JSONObject();
            item.put("path", path);
            item.put("body", json);
            queue.put(item);
            prefs.edit().putString("pendingBackendPosts", queue.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private JSONArray pendingPostQueue() {
        try {
            return new JSONArray(prefs.getString("pendingBackendPosts", "[]"));
        } catch (Exception exception) {
            return new JSONArray();
        }
    }

    private void flushPendingPostsBlocking() {
        if (pendingFlushInProgress) return;
        pendingFlushInProgress = true;
        try {
            JSONArray queue = pendingPostQueue();
            if (queue.length() == 0) return;
            JSONArray remaining = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject item = queue.getJSONObject(i);
                try {
                    sendBackendPost(item.optString("path"), item.optString("body"));
                } catch (Exception exception) {
                    for (int j = i; j < queue.length(); j++) remaining.put(queue.getJSONObject(j));
                    break;
                }
            }
            prefs.edit().putString("pendingBackendPosts", remaining.toString()).apply();
        } catch (Exception ignored) {
        } finally {
            pendingFlushInProgress = false;
        }
    }

    private void startAutoSync() {
        syncHandler.removeCallbacks(autoSyncRunnable);
        syncHandler.postDelayed(autoSyncRunnable, 5000);
    }

    private void stopAutoSync() {
        syncHandler.removeCallbacks(autoSyncRunnable);
    }

    private void syncFromBackend() {
        if (syncInProgress || selectedStaff == null) return;
        syncInProgress = true;
        new Thread(() -> {
            boolean changed = false;
            try {
                flushPendingPostsBlocking();
                HttpURLConnection connection = (HttpURLConnection) new URL(BACKEND_URL + "/api/state").openConnection();
                connection.setRequestMethod("GET");
                String response;
                try (InputStream input = connection.getInputStream()) {
                    response = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                connection.disconnect();

                JSONArray staffList = new JSONObject(response).getJSONArray("staff");
                for (int i = 0; i < staffList.length(); i++) {
                    JSONObject object = staffList.getJSONObject(i);
                    Staff staff = staffById.get(object.optString("id"));
                    if (staff == null) continue;
                    String phone = object.optString("phone", staff.phone);
                    String loggedStaffId = prefs.getString("loggedStaffId", "");
                    String loggedPhone = prefs.getString("loggedPhone", "");
                    if (staff.id.equals(loggedStaffId) && !loggedPhone.isEmpty()) {
                        phone = loggedPhone;
                    }
                    String dob = object.optString("dob", "");
                    double advance = object.optDouble("advance", staff.advanceBalance);
                    double paid = object.optDouble("paid", staff.lastSalaryAmount);
                    double dailyWage = object.optDouble("dailyWage", staff.dailyWage);
                    double monthDays = object.optDouble("monthDays", staff.serverMonthDays);
                    double earned = object.optDouble("earned", staff.serverEarned);
                    double remaining = object.optDouble("remaining", staff.serverRemaining);
                    String lastRepayment = object.optString("lastRepayment", "");
                    if (Math.abs(staff.serverMonthDays - monthDays) > 0.01) {
                        staff.serverMonthDays = monthDays;
                        changed = true;
                    }
                    if (Math.abs(staff.serverEarned - earned) > 0.01) {
                        staff.serverEarned = earned;
                        changed = true;
                    }
                    if (Math.abs(staff.serverRemaining - remaining) > 0.01) {
                        staff.serverRemaining = remaining;
                        changed = true;
                    }
                    if (phone != null && !phone.equals(staff.phone)) {
                        staff.phone = phone;
                        changed = true;
                    }
                    if (!dob.isEmpty()) {
                        LocalDate dateOfBirth = LocalDate.parse(dob);
                        if (staff.dateOfBirth == null || !staff.dateOfBirth.equals(dateOfBirth)) {
                            staff.dateOfBirth = dateOfBirth;
                            changed = true;
                        }
                    }
                    if (Math.abs(staff.dailyWage - dailyWage) > 0.01) {
                        staff.dailyWage = dailyWage;
                        changed = true;
                    }
                    if (Math.abs(staff.advanceBalance - advance) > 0.01) {
                        staff.advanceBalance = advance;
                        changed = true;
                    }
                    if (Math.abs(staff.lastSalaryAmount - paid) > 0.01) {
                        staff.lastSalaryAmount = paid;
                        changed = true;
                    }
                    if (!lastRepayment.isEmpty()) {
                        LocalDate repaymentDate = LocalDate.parse(lastRepayment);
                        if (staff.lastAdvanceReturnDate == null || !staff.lastAdvanceReturnDate.equals(repaymentDate)) {
                            staff.lastAdvanceReturnDate = repaymentDate;
                            changed = true;
                        }
                    }
                    String photo = object.optString("photo", "");
                    if (!photo.isEmpty() && !photo.equals(prefs.getString("photoBitmap:" + staff.id, ""))) {
                        prefs.edit().putString("photoBitmap:" + staff.id, photo).remove("photo:" + staff.id).apply();
                        changed = true;
                    }
                    String todayKey = key(staff.id, LocalDate.now());
                    if (object.optBoolean("attendanceMarked", false)) {
                        String localStatus = localStatus(object.optString("today", ""));
                        double dayValue = "PRESENT".equals(localStatus) || "PAID_HOLIDAY".equals(localStatus) ? 1.0 : "HALF_DAY".equals(localStatus) ? 0.5 : 0;
                        AttendanceRecord current = attendance.get(todayKey);
                        if (current == null || !current.status.equals(localStatus)) {
                            attendance.put(todayKey, new AttendanceRecord(staff.id, LocalDate.now(), localStatus, dayValue));
                            changed = true;
                        }
                    }
                    JSONArray leaves = object.optJSONArray("leaves");
                    if (leaves != null && replaceLeaves(staff, leaves)) {
                        changed = true;
                    }
                    JSONArray attendanceRows = object.optJSONArray("attendanceRows");
                    if (attendanceRows != null && replaceAttendanceRows(staff, attendanceRows)) {
                        changed = true;
                    }
                    JSONArray history = object.optJSONArray("history");
                    if (history != null && replaceHistory(staff, history)) {
                        changed = true;
                    }
                }
            } catch (Exception ignored) {
            }

            boolean shouldRender = changed;
            runOnUiThread(() -> {
                syncInProgress = false;
                if (shouldRender) render();
            });
        }).start();
    }

    private String staffObject(String json, String staffId) {
        String marker = "\"id\":\"" + staffId + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) return "";
        int start = json.lastIndexOf("{", markerIndex);
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return "";
    }

    private boolean replaceLeaves(Staff staff, JSONArray leaves) throws Exception {
        List<LeaveRequest> updated = new ArrayList<>();
        for (int i = 0; i < leaves.length(); i++) {
            JSONObject leave = leaves.getJSONObject(i);
            updated.add(new LeaveRequest(
                    leave.optString("from", ""),
                    leave.optString("to", ""),
                    leave.optString("reason", ""),
                    leave.optString("status", "Pending")
            ));
        }
        if (sameLeaves(staff.leaves, updated)) return false;
        staff.leaves.clear();
        staff.leaves.addAll(updated);
        return true;
    }

    private boolean sameLeaves(List<LeaveRequest> current, List<LeaveRequest> updated) {
        if (current.size() != updated.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            LeaveRequest a = current.get(i);
            LeaveRequest b = updated.get(i);
            if (!a.from.equals(b.from) || !a.to.equals(b.to) || !a.reason.equals(b.reason) || !a.status.equals(b.status)) {
                return false;
            }
        }
        return true;
    }

    private boolean replaceHistory(Staff staff, JSONArray history) throws Exception {
        List<MonthlyHistory> updated = new ArrayList<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject month = history.getJSONObject(i);
            updated.add(new MonthlyHistory(
                    month.optString("month", ""),
                    month.optDouble("days", 0),
                    month.optDouble("earned", 0),
                    month.optDouble("paid", 0),
                    month.optInt("leaves", 0),
                    month.optInt("approvedLeaves", 0)
            ));
        }
        if (sameHistory(staff.history, updated)) return false;
        staff.history.clear();
        staff.history.addAll(updated);
        return true;
    }

    private boolean replaceAttendanceRows(Staff staff, JSONArray rows) throws Exception {
        Map<String, AttendanceRecord> updated = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String dateText = row.optString("date", "");
            if (dateText.isEmpty()) continue;
            LocalDate date = LocalDate.parse(dateText);
            String status = localStatus(row.optString("status", ""));
            double dayValue = row.optDouble("dayValue", "PRESENT".equals(status) || "PAID_HOLIDAY".equals(status) ? 1.0 : "HALF_DAY".equals(status) ? 0.5 : 0);
            updated.put(key(staff.id, date), new AttendanceRecord(staff.id, date, status, dayValue));
        }
        boolean changed = false;
        List<String> existingKeys = new ArrayList<>();
        for (String existing : attendance.keySet()) {
            if (existing.startsWith(staff.id + "|")) existingKeys.add(existing);
        }
        for (String existing : existingKeys) {
            if (!updated.containsKey(existing)) {
                attendance.remove(existing);
                changed = true;
            }
        }
        for (Map.Entry<String, AttendanceRecord> entry : updated.entrySet()) {
            AttendanceRecord current = attendance.get(entry.getKey());
            AttendanceRecord next = entry.getValue();
            if (current == null || !current.status.equals(next.status) || Math.abs(current.dayValue - next.dayValue) > 0.01) {
                attendance.put(entry.getKey(), next);
                changed = true;
            }
        }
        return changed;
    }

    private boolean sameHistory(List<MonthlyHistory> current, List<MonthlyHistory> updated) {
        if (current.size() != updated.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            MonthlyHistory a = current.get(i);
            MonthlyHistory b = updated.get(i);
            if (!a.month.equals(b.month) || Math.abs(a.days - b.days) > 0.01
                    || Math.abs(a.earned - b.earned) > 0.01 || Math.abs(a.paid - b.paid) > 0.01
                    || a.leaves != b.leaves || a.approvedLeaves != b.approvedLeaves) {
                return false;
            }
        }
        return true;
    }

    private double jsonDouble(String object, String key, double fallback) {
        String marker = "\"" + key + "\":";
        int start = object.indexOf(marker);
        if (start < 0) return fallback;
        start += marker.length();
        int end = start;
        while (end < object.length() && "0123456789.-".indexOf(object.charAt(end)) >= 0) {
            end++;
        }
        try {
            return Double.parseDouble(object.substring(start, end));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String jsonString(String object, String key) {
        String marker = "\"" + key + "\":\"";
        int start = object.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = object.indexOf("\"", start);
        return end < 0 ? "" : object.substring(start, end);
    }

    private boolean jsonBoolean(String object, String key, boolean fallback) {
        String marker = "\"" + key + "\":";
        int start = object.indexOf(marker);
        if (start < 0) return fallback;
        start += marker.length();
        if (object.startsWith("true", start)) return true;
        if (object.startsWith("false", start)) return false;
        return fallback;
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void getCurrentLocation(LocationCallback callback) {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            callback.onLocation(null);
            return;
        }

        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location best = newestLocation(gps, network);
            if (best != null && best.getAccuracy() <= 100 && System.currentTimeMillis() - best.getTime() < 120000) {
                callback.onLocation(best);
                return;
            }

            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    locationManager.removeUpdates(this);
                    callback.onLocation(location);
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null);
        } catch (SecurityException | IllegalArgumentException exception) {
            callback.onLocation(null);
        }
    }

    private Location newestLocation(Location first, Location second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.getTime() >= second.getTime() ? first : second;
    }

    private String currentWifiSsid() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager == null) return "";
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getSSID() == null) return "";
            return info.getSSID().replace("\"", "").replace("<unknown ssid>", "");
        } catch (Exception exception) {
            return "";
        }
    }

    private Location shopLocation() {
        Location location = new Location("shop");
        if (prefs.getInt("shopLockVersion", 0) == SHOP_LOCK_VERSION) {
            location.setLatitude(prefs.getFloat("shopLat", (float) DEFAULT_SHOP_LAT));
            location.setLongitude(prefs.getFloat("shopLon", (float) DEFAULT_SHOP_LON));
        } else {
            location.setLatitude(DEFAULT_SHOP_LAT);
            location.setLongitude(DEFAULT_SHOP_LON);
        }
        return location;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingShopLocationSetup) {
                pendingShopLocationSetup = false;
                setCurrentLocationAsShop();
            } else {
                markPresent();
            }
        } else if (requestCode == PHONE_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingLoginPhone != null) {
                completePhoneLogin(pendingLoginPhone);
                pendingLoginPhone = null;
            }
        } else {
            toast(requestCode == PHONE_REQUEST ? "Phone permission is required for SIM check" : "Location permission is required");
        }
    }

    private boolean hasPhonePermission() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private List<String> simPhoneNumbers() {
        List<String> numbers = new ArrayList<>();
        if (!hasPhonePermission()) return numbers;
        try {
            SubscriptionManager manager = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (manager == null) return numbers;
            List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
            if (subscriptions == null) return numbers;
            for (SubscriptionInfo info : subscriptions) {
                String number;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    number = manager.getPhoneNumber(info.getSubscriptionId());
                } else {
                    number = info.getNumber();
                }
                String normalized = number == null ? "" : number.replaceAll("[^0-9]", "");
                if (normalized.length() >= 10) {
                    numbers.add(normalized.substring(normalized.length() - 10));
                }
            }
        } catch (SecurityException | IllegalStateException exception) {
            return new ArrayList<>();
        }
        return numbers;
    }

    private boolean isAdmin() {
        return "admin".equals(prefs.getString("loggedRole", "")) || "ADM001".equals(prefs.getString("loggedStaffId", ""));
    }

    private String normalizePhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private String todayStatus(String staffId) {
        AttendanceRecord record = attendance.get(key(staffId, LocalDate.now()));
        return record == null ? "NOT_MARKED" : record.status;
    }

    private double attendanceDays(String staffId, LocalDate start, LocalDate end) {
        double days = 0;
        LocalDate date = start;
        while (!date.isAfter(end)) {
            AttendanceRecord record = attendance.get(key(staffId, date));
            if (record != null && date.getDayOfWeek() != DayOfWeek.SUNDAY) days += record.dayValue;
            date = date.plusDays(1);
        }
        return days;
    }

    private LocalDate nextSaturday(LocalDate today) {
        return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
    }

    private String nextRepaymentDate(LocalDate today, Staff staff) {
        return staff.advanceBalance <= 0 ? "No advance due" : nextSaturday(today).toString();
    }

    private double weeklyRepayment(LocalDate today, Staff staff) {
        if (staff.advanceBalance <= 0) return 0;
        LocalDate cursor = nextSaturday(today);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        int count = 0;
        while (!cursor.isAfter(monthEnd)) {
            count++;
            cursor = cursor.plusWeeks(1);
        }
        double plannedRepayment = staff.advanceBalance / Math.max(1, count);
        return Math.min(plannedRepayment, maxWeeklyRepayment(staff));
    }

    private double maxWeeklyRepayment(Staff staff) {
        return staff.dailyWage * 6 * 0.5;
    }

    private LinearLayout iconCard(int iconRes, int accentColor, int backgroundColor, String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(roundRect(backgroundColor, 8));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(params);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(accentColor);
        GradientDrawable iconBackground = roundRect(Color.WHITE, 8);
        icon.setBackground(iconBackground);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.setMargins(0, 0, dp(10), 0);
        card.addView(icon, iconParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(title, 15, true);
        heading.setTextColor(accentColor);
        TextView detail = text(body, 13, false);
        detail.setTextColor(Color.rgb(39, 51, 64));
        detail.setPadding(0, dp(4), 0, 0);
        copy.addView(heading);
        copy.addView(detail);
        card.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return card;
    }

    private LinearLayout metricCard(int iconRes, int accentColor, int backgroundColor, String title, String[][] metrics) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(roundRect(backgroundColor, 8));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(params);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(accentColor);
        icon.setBackground(roundRect(Color.WHITE, 8));
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        header.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView heading = text(title, 16, true);
        heading.setTextColor(accentColor);
        heading.setPadding(dp(10), 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(header);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(6), 0, 0);
        for (int index = 0; index < metrics.length; index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(metricBox(metrics[index][0], metrics[index][1], accentColor), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (index + 1 < metrics.length) {
                TextView gap = new TextView(this);
                row.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
                row.addView(metricBox(metrics[index + 1][0], metrics[index + 1][1], accentColor), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, dp(6), 0, 0);
            grid.addView(row, rowParams);
        }
        card.addView(grid);
        return card;
    }

    private LinearLayout metricBox(String label, String value, int accentColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackground(roundRect(Color.WHITE, 8));
        box.setElevation(dp(1));
        TextView labelView = text(label, 11, true);
        labelView.setTextColor(Color.rgb(94, 110, 126));
        TextView valueView = text(value, 15, true);
        valueView.setTextColor(accentColor);
        valueView.setPadding(0, dp(2), 0, 0);
        box.addView(labelView);
        box.addView(valueView);
        return box;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(0xff17212b);
        textView.setLineSpacing(dp(3), 1.0f);
        if (bold) textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return textView;
    }

    private View employeeAvatar(Staff staff, int sizePx) {
        Bitmap cameraBitmap = bitmapFromBase64(prefs.getString("photoBitmap:" + staff.id, ""));
        if (cameraBitmap != null) {
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(cameraBitmap);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackground(roundRect(Color.WHITE, 32));
            return imageView;
        }

        String photoUri = prefs.getString("photo:" + staff.id, "");
        if (!photoUri.isEmpty()) {
            ImageView imageView = new ImageView(this);
            imageView.setImageURI(Uri.parse(photoUri));
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackground(roundRect(Color.WHITE, 32));
            return imageView;
        }

        TextView avatar = text(initials(staff.name), sizePx >= dp(70) ? 22 : 17, true);
        avatar.setGravity(android.view.Gravity.CENTER);
        avatar.setTextColor(Color.WHITE);
        avatar.setBackground(roundRect(Color.rgb(47, 111, 115), 32));
        return avatar;
    }

    private String key(String staffId, LocalDate date) {
        return staffId + "|" + date;
    }

    private String blankDate(LocalDate date) {
        return date == null ? "No repayment yet" : date.toString();
    }

    private String money(double value) {
        return String.format("%.2f", value);
    }

    private String displayStatus(String status) {
        if ("NOT_MARKED".equals(status)) return "Not marked";
        if ("HALF_DAY".equals(status)) return "Half day";
        if ("PRESENT".equals(status)) return "Full day";
        if ("ABSENT".equals(status)) return "Absent";
        if ("PAID_HOLIDAY".equals(status)) return "Paid holiday";
        if ("SUNDAY_HOLIDAY".equals(status)) return "Sunday holiday";
        return status;
    }

    private String backendStatus(String status) {
        if ("PRESENT".equals(status)) return "Full day";
        if ("HALF_DAY".equals(status)) return "Half day";
        if ("ABSENT".equals(status)) return "Absent";
        if ("PAID_HOLIDAY".equals(status)) return "Paid holiday";
        if ("SUNDAY_HOLIDAY".equals(status)) return "Sunday holiday";
        return status;
    }

    private String localStatus(String status) {
        if ("Full day".equalsIgnoreCase(status) || "PRESENT".equalsIgnoreCase(status)) return "PRESENT";
        if ("Half day".equalsIgnoreCase(status) || "HALF_DAY".equalsIgnoreCase(status)) return "HALF_DAY";
        if ("Absent".equalsIgnoreCase(status) || "ABSENT".equalsIgnoreCase(status)) return "ABSENT";
        if ("Paid holiday".equalsIgnoreCase(status) || "PAID_HOLIDAY".equalsIgnoreCase(status)) return "PAID_HOLIDAY";
        if ("Sunday holiday".equalsIgnoreCase(status) || "SUNDAY_HOLIDAY".equalsIgnoreCase(status)) return "SUNDAY_HOLIDAY";
        return status;
    }

    private int statusColor(String status) {
        if ("PRESENT".equals(status)) return Color.rgb(18, 109, 92);
        if ("HALF_DAY".equals(status)) return Color.rgb(138, 75, 18);
        if ("PAID_HOLIDAY".equals(status)) return Color.rgb(31, 93, 143);
        if ("SUNDAY_HOLIDAY".equals(status)) return Color.rgb(94, 110, 126);
        return Color.rgb(154, 43, 43);
    }

    private int lightStatusColor(String status) {
        if ("PRESENT".equals(status)) return Color.rgb(232, 246, 242);
        if ("HALF_DAY".equals(status)) return Color.rgb(255, 244, 222);
        if ("PAID_HOLIDAY".equals(status)) return Color.rgb(232, 241, 249);
        if ("SUNDAY_HOLIDAY".equals(status)) return Color.rgb(244, 247, 250);
        return Color.rgb(255, 235, 235);
    }

    private String shortStatus(String status) {
        if ("PRESENT".equals(status)) return "P";
        if ("HALF_DAY".equals(status)) return "H";
        if ("ABSENT".equals(status)) return "A";
        if ("PAID_HOLIDAY".equals(status)) return "PH";
        if ("SUNDAY_HOLIDAY".equals(status)) return "Sun";
        return "-";
    }

    private String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase();
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private int systemBarHeight(String name) {
        int resourceId = getResources().getIdentifier(name + "_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    static class Staff {
        final String id;
        final String name;
        double dailyWage;
        String nickname;
        LocalDate dateOfBirth;
        String phone;
        LocalDate dateOfJoining;
        String emergencyContact;
        double advanceBalance;
        LocalDate lastSalaryDate;
        LocalDate lastSalaryThroughDate;
        double lastSalaryAmount;
        LocalDate lastAdvanceReturnDate;
        double serverMonthDays = -1;
        double serverEarned = -1;
        double serverRemaining = -1;
        final List<LeaveRequest> leaves = new ArrayList<>();
        final List<MonthlyHistory> history = new ArrayList<>();

        Staff(String id, String name, double dailyWage) {
            this.id = id;
            this.name = name;
            this.dailyWage = dailyWage;
        }
    }

    static class LeaveRequest {
        final String from;
        final String to;
        final String reason;
        String status;

        LeaveRequest(String from, String to, String reason, String status) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.status = status;
        }
    }

    static class MonthlyHistory {
        final String month;
        final double days;
        final double earned;
        final double paid;
        final int leaves;
        final int approvedLeaves;

        MonthlyHistory(String month, double days, double earned, double paid, int leaves, int approvedLeaves) {
            this.month = month;
            this.days = days;
            this.earned = earned;
            this.paid = paid;
            this.leaves = leaves;
            this.approvedLeaves = approvedLeaves;
        }
    }

    static class AttendanceRecord {
        final String staffId;
        final LocalDate date;
        final String status;
        final double dayValue;

        AttendanceRecord(String staffId, LocalDate date, String status, double dayValue) {
            this.staffId = staffId;
            this.date = date;
            this.status = status;
            this.dayValue = dayValue;
        }
    }

    interface LocationCallback {
        void onLocation(Location location);
    }

    interface AmountCallback {
        void onAmount(double amount);
    }
}
