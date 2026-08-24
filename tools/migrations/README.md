# One-Time Migration Tools

These Java files were used only to import or clean historical test data.

They are not part of the running backend. The live app now reads and writes data through:

- local DB fallback: `data/company-attendance.db`
- cloud DB when configured: `DATABASE_URL`
- backend API actions in `src/Main.java`

Do not rerun these tools unless you intentionally want to overwrite or clean imported historical data.

Past one-time jobs:

- `ImportLedgers.java`: imported advance/repayment PDF ledger rows.
- `ImportSalaryDoc.java`: imported the salary Word document as initial monthly totals.
- `ReassignRahulLogin.java`: removed Rohit and mapped `8962569527` to Rahul Joshi.
- `CleanupTodaySalaryRows.java`: removed accidental test salary payment rows.
