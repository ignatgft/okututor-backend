create table if not exists availability_rules (
    id uuid primary key,
    tutor_id uuid not null references users(id) on delete cascade,
    weekday varchar(15) not null,
    start_time time not null,
    end_time time not null,
    timezone varchar(50) default 'UTC'
);

create table if not exists availability_exceptions (
    id uuid primary key,
    tutor_id uuid not null references users(id) on delete cascade,
    exception_date date not null,
    is_available boolean not null,
    start_time time,
    end_time time
);

create table if not exists blocked_times (
    id uuid primary key,
    tutor_id uuid not null references users(id) on delete cascade,
    start_at timestamp with time zone not null,
    end_at timestamp with time zone not null,
    reason varchar(255)
);

create table if not exists time_offs (
    id uuid primary key,
    tutor_id uuid not null references users(id) on delete cascade,
    start_date date not null,
    end_date date not null,
    reason varchar(255)
);

create table if not exists booking_proposals (
    id uuid primary key,
    booking_id uuid not null references bookings(id) on delete cascade,
    proposed_by uuid not null references users(id) on delete cascade,
    start_at timestamp with time zone not null,
    end_at timestamp with time zone not null,
    status varchar(20) not null default 'PENDING',
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null
);

create index if not exists idx_availability_rules_tutor on availability_rules(tutor_id);
create index if not exists idx_availability_exceptions_tutor_date on availability_exceptions(tutor_id, exception_date);
create index if not exists idx_blocked_times_tutor_range on blocked_times(tutor_id, start_at, end_at);
create index if not exists idx_time_offs_tutor_dates on time_offs(tutor_id, start_date, end_date);
create index if not exists idx_booking_proposals_booking on booking_proposals(booking_id);
