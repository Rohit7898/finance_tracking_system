# Database Setup

Current storage:
- Cloud DB when `DATABASE_URL` is set: Neon Postgres
- Local fallback when `DATABASE_URL` is missing: `data/company-attendance.db`
- Backend owner: `src/Main.java`
- SQL schema: `db/schema.sql`
- One-time import/cleanup scripts live in `tools/migrations/` and are not part of the running app.

Deployment direction:
- Shop phone APK and remote admin must use the same backend URL.
- A local Mac IP like `192.168.29.12` only works inside the same Wi-Fi.
- For admin access from another location, run the backend on a hosted server/VPS/cloud app with HTTPS.
- Keep the database on that hosted backend, not inside the APK.
- The shop phone should only submit attendance/leave/photo data to the hosted API.
- Admin browser should update salary, advance, repayment, and employee details through the same hosted API.
- Suggested free stack: Koyeb web service + Neon Postgres.
- Setup guide: `docs/koyeb-neon-setup.md`

Planned SQL tables:

## users
| Column | Purpose |
| --- | --- |
| id | Employee/admin id like `OP001`, `ADM001` |
| role | `employee` or `admin` |
| full_name | Legal/display name |
| nickname | Short name |
| phone | Login phone |
| dob | Date of birth |
| joining_date | Date of joining |
| emergency_contact | Emergency phone/person |
| daily_wage | Current salary/day for future attendance |
| photo | Profile image path/blob |

## attendance
| Column | Purpose |
| --- | --- |
| id | Attendance row id |
| user_id | Employee id |
| date | Attendance date |
| status | `Full day`, `Half day`, `Absent` |
| day_value | `1.0`, `0.5`, or `0` |
| wage_at_time | Salary/day locked for that date |
| source | employee, auto, or admin |

## leaves
| Column | Purpose |
| --- | --- |
| id | Leave request id |
| user_id | Employee id |
| from_date | Leave start |
| to_date | Leave end |
| reason | Dropdown reason |
| status | Pending, Approved, Rejected |
| decided_by | Admin id |
| decided_at | Approval/rejection time |

## salary_payments
| Column | Purpose |
| --- | --- |
| id | Salary payment id |
| user_id | Employee id |
| date | Payment date |
| amount | Salary paid |
| month_key | Salary month, e.g. `2026-08` |

## advance_transactions
| Column | Purpose |
| --- | --- |
| id | Transaction id |
| user_id | Employee id |
| date | Transaction date |
| type | `ADVANCE` or `REPAYMENT` |
| amount | Transaction amount |
| balance_after | Advance balance after transaction |

Important rules:
- Salary/day changes only affect future attendance.
- Attendance rows store `wage_at_time` so old salary does not change.
- Leave requests cannot overlap existing Pending or Approved leave.
- Approved leave blocks attendance and marks that day absent.
- Approved leave history counts unique leave dates, not request count.
