# Koyeb + Neon Setup

This setup makes the APK and admin browser sync through one public backend.

## 1. Create Neon Postgres

1. Create a Neon project.
2. Open the Neon SQL editor.
3. Run `db/schema.sql`.
4. Copy the pooled connection string.

Use a JDBC-style value for the backend:

```text
DATABASE_URL=jdbc:postgresql://HOST/DATABASE?user=USER&password=PASSWORD&sslmode=require
```

If Neon gives this format:

```text
postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require
```

that also works. The Java backend automatically prefixes `jdbc:`.

## 2. Push Code To GitHub

Koyeb will build from this repository. The important files are:

- `Dockerfile`
- `src/Main.java`
- `db/schema.sql`

Do not commit local runtime data:

- `data/company-attendance.db`
- `app/build`
- `out`

## 3. Create Koyeb Web Service

1. Create a new Koyeb app from GitHub.
2. Select this repository.
3. Use Dockerfile deployment.
4. Set environment variables:

```text
DATABASE_URL=postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require
PORT=8080
```

5. Deploy.

After deploy, Koyeb will give a public URL like:

```text
https://your-app.koyeb.app
```

Admin page:

```text
https://your-app.koyeb.app
```

API test:

```text
https://your-app.koyeb.app/api/state
```

## 4. Update APK Backend URL

Replace the local Wi-Fi URL in the Android app with the Koyeb URL:

```text
http://192.168.29.12:8080
```

to:

```text
https://your-app.koyeb.app
```

Then rebuild the APK.

## 5. Data Behavior

When `DATABASE_URL` is set:

- Employee profiles save in Neon.
- Attendance saves in Neon.
- Leaves save in Neon.
- Salary payments save in Neon.
- Advance and repayment history saves in Neon.
- Profile photos are saved as base64 text in Neon for now.

When `DATABASE_URL` is not set:

- The backend keeps using local file storage at `data/company-attendance.db`.

## 6. Current Limit

The current Java backend keeps data in memory and writes the full small dataset to Postgres on each change. This is acceptable for 7 employees and admin testing. Later, when the app becomes production, update each API to write only the changed row.
