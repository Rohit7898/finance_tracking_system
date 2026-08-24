import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class ImportLedgers {
    private static final Path DB_FILE = Path.of("data", "company-attendance.db");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Main.Staff> staff;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(DB_FILE))) {
            Object loaded = input.readObject();
            staff = loaded instanceof Main.AppData data ? data.staff : (Map<String, Main.Staff>) loaded;
        }

        apply(staff.get("OP002"), ledger(new Object[][]{
                {"2026-07-01", "ADVANCE", 26710.00},
                {"2026-07-18", "REPAYMENT", 740.00},
                {"2026-07-22", "ADVANCE", 1000.00},
                {"2026-07-25", "REPAYMENT", 1000.00},
                {"2026-08-07", "REPAYMENT", 700.00},
                {"2026-08-11", "ADVANCE", 500.00},
                {"2026-08-14", "ADVANCE", 500.00},
                {"2026-08-19", "REPAYMENT", 1500.00}
        }));
        apply(staff.get("OP003"), ledger(new Object[][]{
                {"2026-07-01", "ADVANCE", 1200.00},
                {"2026-07-02", "REPAYMENT", 100.00},
                {"2026-07-02", "ADVANCE", 100.00},
                {"2026-07-08", "ADVANCE", 200.00},
                {"2026-07-12", "REPAYMENT", 200.00},
                {"2026-07-14", "ADVANCE", 200.00},
                {"2026-07-18", "REPAYMENT", 200.00},
                {"2026-07-23", "ADVANCE", 100.00},
                {"2026-07-25", "REPAYMENT", 100.00},
                {"2026-07-28", "ADVANCE", 300.00},
                {"2026-07-29", "ADVANCE", 500.00},
                {"2026-08-07", "REPAYMENT", 400.00},
                {"2026-08-11", "ADVANCE", 200.00},
                {"2026-08-14", "ADVANCE", 1000.00},
                {"2026-08-14", "ADVANCE", 4000.00},
                {"2026-08-16", "ADVANCE", 1760.00}
        }));
        apply(staff.get("OP004"), ledger(new Object[][]{
                {"2026-07-01", "ADVANCE", 2430.00},
                {"2026-07-02", "REPAYMENT", 500.00},
                {"2026-07-11", "ADVANCE", 50.00},
                {"2026-07-14", "ADVANCE", 200.00},
                {"2026-07-18", "REPAYMENT", 420.00},
                {"2026-07-24", "ADVANCE", 200.00},
                {"2026-07-25", "REPAYMENT", 520.00},
                {"2026-08-01", "REPAYMENT", 580.00},
                {"2026-08-03", "ADVANCE", 50.00},
                {"2026-08-07", "ADVANCE", 200.00},
                {"2026-08-07", "REPAYMENT", 720.00},
                {"2026-08-08", "ADVANCE", 2000.00},
                {"2026-08-19", "ADVANCE", 100.00}
        }));
        apply(staff.get("OP005"), ledger(new Object[][]{
                {"2026-04-01", "ADVANCE", 280.00},
                {"2026-04-10", "ADVANCE", 2000.00},
                {"2026-05-06", "REPAYMENT", 2000.00},
                {"2026-05-15", "ADVANCE", 4000.00},
                {"2026-06-01", "REPAYMENT", 4280.00},
                {"2026-06-15", "ADVANCE", 2000.00},
                {"2026-07-02", "REPAYMENT", 2000.00},
                {"2026-07-18", "ADVANCE", 4000.00},
                {"2026-07-29", "ADVANCE", 25000.00},
                {"2026-08-01", "REPAYMENT", 4000.00}
        }));
        apply(staff.get("OP007"), ledger(new Object[][]{
                {"2026-04-01", "ADVANCE", 1600.00},
                {"2026-04-04", "REPAYMENT", 500.00},
                {"2026-06-01", "REPAYMENT", 560.00},
                {"2026-07-01", "REPAYMENT", 1500.00},
                {"2026-07-01", "ADVANCE", 1500.00}
        }));
        apply(staff.get("OP008"), ledger(new Object[][]{
                {"2026-04-01", "ADVANCE", 32960.00},
                {"2026-04-04", "REPAYMENT", 2740.00},
                {"2026-05-06", "REPAYMENT", 2900.00},
                {"2026-06-22", "REPAYMENT", 50.00},
                {"2026-08-01", "REPAYMENT", 5000.00}
        }));

        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(DB_FILE))) {
            output.writeObject(new LinkedHashMap<>(staff));
        }
        System.out.println("Imported advance ledgers into " + DB_FILE.toAbsolutePath());
    }

    private static ArrayList<Main.AdvanceTransaction> ledger(Object[][] rows) {
        ArrayList<Main.AdvanceTransaction> transactions = new ArrayList<>();
        double balance = 0;
        for (Object[] row : rows) {
            String date = (String) row[0];
            String type = (String) row[1];
            double amount = (Double) row[2];
            balance += "ADVANCE".equals(type) ? amount : -amount;
            transactions.add(new Main.AdvanceTransaction(date, type, amount, balance));
        }
        return transactions;
    }

    private static void apply(Main.Staff employee, ArrayList<Main.AdvanceTransaction> ledger) {
        if (employee == null) return;
        employee.advanceTransactions = ledger;
        employee.advanceBalance = ledger.isEmpty() ? 0 : ledger.get(ledger.size() - 1).balanceAfter;
        employee.lastRepaymentDate = ledger.stream()
                .filter(transaction -> "REPAYMENT".equals(transaction.type))
                .reduce((first, second) -> second)
                .map(transaction -> transaction.date)
                .orElse("");
    }
}
