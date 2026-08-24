create table if not exists staff (
    id text primary key,
    role text not null default 'employee',
    full_name text not null,
    nickname text,
    phone text,
    dob date,
    joining_date date,
    emergency_contact text,
    daily_wage numeric(10,2) not null default 0,
    advance_balance numeric(10,2) not null default 0,
    last_salary_amount numeric(10,2) not null default 0,
    last_salary_date date,
    last_repayment_date date,
    photo_base64 text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists attendance (
    staff_id text not null references staff(id) on delete cascade,
    attendance_date date not null,
    status text not null check (status in ('Full day', 'Half day', 'Absent', 'Paid holiday', 'Sunday holiday')),
    day_value numeric(4,2) not null,
    earned_wage numeric(10,2) not null,
    source text not null default 'employee',
    created_at timestamptz not null default now(),
    primary key (staff_id, attendance_date)
);

create table if not exists leaves (
    id bigserial primary key,
    staff_id text not null references staff(id) on delete cascade,
    from_date date not null,
    to_date date not null,
    reason text,
    status text not null default 'Pending' check (status in ('Pending', 'Approved', 'Rejected')),
    decided_by text,
    decided_at timestamptz,
    created_at timestamptz not null default now(),
    check (to_date >= from_date)
);

create table if not exists salary_payments (
    id bigserial primary key,
    staff_id text not null references staff(id) on delete cascade,
    paid_date date not null,
    amount numeric(10,2) not null check (amount > 0),
    month_key text,
    created_at timestamptz not null default now()
);

create table if not exists advance_transactions (
    id bigserial primary key,
    staff_id text not null references staff(id) on delete cascade,
    transaction_date date not null,
    type text not null check (type in ('ADVANCE', 'REPAYMENT')),
    amount numeric(10,2) not null check (amount > 0),
    balance_after numeric(10,2) not null,
    created_at timestamptz not null default now()
);

create table if not exists public_holidays (
    holiday_date date primary key,
    title text not null default 'Public holiday',
    created_at timestamptz not null default now()
);

create index if not exists idx_attendance_staff_month on attendance (staff_id, attendance_date);
create index if not exists idx_leaves_staff_dates on leaves (staff_id, from_date, to_date);
create index if not exists idx_salary_staff_date on salary_payments (staff_id, paid_date);
create index if not exists idx_advance_staff_date on advance_transactions (staff_id, transaction_date);
