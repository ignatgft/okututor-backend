import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ── Metrics ────────────────────────────────────────────────────────────────────
const bookingCreated   = new Counter('bookings_created');
const bookingConfirmed = new Counter('bookings_confirmed');
const searchHits       = new Counter('search_hits');
const httpErrors       = new Counter('http_errors');
const apiLatency       = new Trend('api_latency');

// ── Config ─────────────────────────────────────────────────────────────────────
const BASE    = __ENV.BASE_URL || 'http://localhost:8080';
const STUDENT = { email: 'test@test.com',        password: 'Student#12345' };
const TUTOR   = { email: 'tutor@test.com',        password: 'Tutor#12345'   };
const ADMIN   = { email: 'super@admin.test',      password: 'Admin#12345' };

function futureDates() {
    const d = [];
    for (let i = 1; i <= 14; i++) {
        const dt = new Date(); dt.setDate(dt.getDate() + i);
        d.push(dt.toISOString().split('T')[0]);
    }
    return d;
}
const FUTURE_DATES = futureDates();

// ── Helpers ────────────────────────────────────────────────────────────────────
function api(method, path, body, token) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const opts = { headers, timeout: '30s' };
    if (body) opts.body = JSON.stringify(body);
    let res;
    if (method === 'GET')        res = http.get(BASE + path, opts);
    else if (method === 'POST') res = http.post(BASE + path, opts.body, opts);
    else if (method === 'PUT')  res = http.put(BASE + path, opts.body, opts);
    else                         res = http.del(BASE + path, null, opts);
    apiLatency.add(res.timings.duration);
    if (res.status >= 400) httpErrors.add(1);
    return res;
}

function ok(res, status, tag) {
    const okStatus = Array.isArray(status) ? status : [status];
    return check(res, { [tag + ' ' + status]: r => okStatus.includes(r.status) });
}

function login(creds) {
    const r = api('POST', '/api/v1/auth/login', { email: creds.email, password: creds.password });
    return r.json();
}

// ── Setup: single login per role ───────────────────────────────────────────────
export function setup() {
    console.log('[setup] logging in...');
    const s = login(STUDENT);
    const t = login(TUTOR);
    const a = login(ADMIN);
    console.log('[setup] tokens: student=' + !!s.access_token + ' tutor=' + !!t.access_token + ' admin=' + !!a.access_token);
    return {
        s: s.access_token || '',
        t: t.access_token || '',
        a: a.access_token || '',
        tutorId: t.user?.id || '',
    };
}

// ── Single scenario: step-by-step flow per VU ─────────────────────────────────
export default function (data) {
    // ──── PHASE 1: Public browse (no auth) ────────────────────────────────
    group('Public: list', () => {
        const r = api('GET', '/api/v1/courses?page=0&size=10');
        ok(r, 200, 'courses');
        if (r.status === 200) searchHits.add(1);
    });
    group('Public: search filters', () => {
        ok(api('GET', '/api/v1/courses?subject=IT&price_min=100&rating_min=4&location_type=online'), 200, 'search');
    });
    group('Public: popular', () => {
        ok(api('GET', '/api/v1/courses/popular'), 200, 'popular');
    });
    group('Public: byId', () => {
        const r = api('GET', '/api/v1/courses?page=0&size=1');
        const id = r.json()?.content?.[0]?.id;
        if (id) ok(api('GET', '/api/v1/courses/' + id), 200, 'byId');
    });
    group('Public: tutors', () => {
        ok(api('GET', '/api/v1/users/tutors?page=0&size=5'), 200, 'tutors');
    });
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 2: Auth ────────────────────────────────────────────────────
    group('Auth: me student', () => ok(api('GET', '/api/v1/users/me', null, data.s), 200, 'me-student'));
    group('Auth: me tutor',   () => ok(api('GET', '/api/v1/users/me', null, data.t), 200, 'me-tutor'));
    group('Auth: me admin',   () => ok(api('GET', '/api/v1/users/me', null, data.a), 200, 'me-admin'));
    group('Auth: refresh', () => {
        const fresh = login(STUDENT);
        if (fresh.refresh_token) ok(api('POST', '/api/v1/auth/refresh', { refresh_token: fresh.refresh_token }), 200, 'refresh');
    });
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 3: Tutor course CRUD ───────────────────────────────────────
    let courseId;
    group('Tutor: create course', () => {
        const r = api('POST', '/api/v1/courses', {
            title: 'k6-' + Date.now() + '-' + randomIntBetween(1000, 9999),
            description: 'k6 test course',
            subject: randomItem(['IT', 'Math', 'English']),
            category: null,
            days: ['weekdays'], specific_days: [],
            group_size: 'individual', location_type: 'online',
            experience: randomIntBetween(1, 5), price_per_hour: randomIntBetween(100, 500),
            currency: 'KGS', max_students: 1, status: 'DRAFT',
        }, data.t);
        ok(r, 200, 'create-course');
        courseId = r.json()?.id;
    });
    if (courseId) {
        group('Tutor: view own',   () => ok(api('GET', '/api/v1/courses/' + courseId, null, data.t), 200, 'view-own'));
        group('Tutor: update',     () => ok(api('PUT', '/api/v1/courses/' + courseId, {
            title: 'k6-updated', description: 'Updated', subject: 'IT', category: null,
            days: ['weekdays', 'weekends'], specific_days: [], group_size: 'individual',
            location_type: 'online', experience: 3, price_per_hour: 350,
            currency: 'KGS', max_students: 1, status: 'DRAFT',
        }, data.t), 200, 'update'));
        group('Tutor: delete',     () => ok(api('DELETE', '/api/v1/courses/' + courseId, null, data.t), 204, 'delete'));
    }
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 4: Enrollment on the k6 tutor's approved course → accept ───
    // Booking теперь доступен только после ACCEPTED заявки (403 NOT_ELIGIBLE иначе),
    // поэтому берём APPROVED курс тьютора, студент подаёт заявку, тьютор принимает.
    // Студент/тьютор/курс общие для всех VU, поэтому переиспользуем существующую заявку,
    // если она уже создана предыдущим прогоном.
    let bookCourseId, enrollmentId;
    group('Enroll: tutor courses', () => {
        const r = api('GET', '/api/v1/courses/teacher/' + data.tutorId, null, data.t);
        ok(r, 200, 'tutor-courses');
        if (r.status === 200) bookCourseId = r.json()?.content?.[0]?.id;
    });
    if (bookCourseId) {
        group('Enroll: enroll', () => {
            const r = api('POST', '/api/v1/courses/' + bookCourseId + '/enroll',
                { message: 'k6', preferred_schedule: 'weekdays' }, data.s);
            ok(r, [200, 201, 409], 'enroll');
            if (r.status === 200 || r.status === 201) enrollmentId = r.json()?.id;
        });
        if (!enrollmentId) {
            // уже была заявка → берём существующую
            const cur = api('GET', '/api/v1/courses/' + bookCourseId + '/enrollment', null, data.s);
            if (cur.status === 200 && cur.json()?.id) enrollmentId = cur.json().id;
        }
        if (enrollmentId) {
            group('Enroll: tutor accept', () => {
                ok(api('POST', '/api/v1/enrollments/' + enrollmentId + '/accept', null, data.t),
                   [200, 201, 409], 'enroll-accept');
            });
        }
        group('Enroll: check', () => ok(api('GET', '/api/v1/courses/' + bookCourseId + '/enrollment', null, data.s), 200, 'enroll-check'));
        group('Enroll: student list', () => ok(api('GET', '/api/v1/students/me/enrollments', null, data.s), 200, 'student-enrollments'));
        group('Enroll: tutor requests', () => ok(api('GET', '/api/v1/tutors/me/requests', null, data.t), 200, 'tutor-requests'));
    }
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 5: Booking flow (requires ACCEPTED enrollment) ─────────────
    let bookingId;
    if (bookCourseId && enrollmentId) {
        group('Booking: create', () => {
            const r = api('POST', '/api/v1/bookings', {
                course_id: bookCourseId, enrollment_id: enrollmentId,
                date: randomItem(FUTURE_DATES), time: '14:00', duration_minutes: 60,
                timezone: 'Asia/Bishkek',
            }, data.s);
            // 200/201 = создано; 409 = слот уже занят (гонка на общем курсе)
            ok(r, [200, 201, 409], 'create-booking');
            if (r.status === 200 || r.status === 201) {
                bookingId = r.json()?.id;
                bookingCreated.add(1);
            }
        });
        if (bookingId) {
            group('Booking: byId',   () => ok(api('GET', '/api/v1/bookings/' + bookingId, null, data.s), 200, 'booking-byId'));
            group('Booking: confirm',() => {
                ok(api('POST', '/api/v1/bookings/' + bookingId + '/confirm', null, data.t), 200, 'confirm');
                bookingConfirmed.add(1);
            });
            // дата урока 1-14 дней в будущем → вне окна входа → 403 MEETING_NOT_AVAILABLE
            group('Booking: meeting',() => ok(api('POST', '/api/v1/bookings/' + bookingId + '/meeting/token', null, data.s), [200, 403, 409], 'meeting'));
        }
        group('Booking: student list', () => ok(api('GET', '/api/v1/bookings/me', null, data.s), 200, 'student-bookings'));
        group('Booking: tutor list',   () => ok(api('GET', '/api/v1/bookings/teacher', null, data.t), 200, 'tutor-bookings'));
    }
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 6: Support ─────────────────────────────────────────────────
    let ticketId;
    group('Support: create', () => {
        const r = api('POST', '/api/v1/support/tickets', {
            category: 'TECHNICAL', subject: 'k6-' + Date.now(),
            description: 'k6 ticket', priority: 'NORMAL',
        }, data.s);
        ok(r, [200, 201, 409], 'create-ticket');
        ticketId = r.json()?.id;
    });
    if (ticketId) {
        group('Support: list',    () => ok(api('GET', '/api/v1/support/tickets', null, data.s), 200, 'list-tickets'));
        group('Support: add msg', () => ok(api('POST', '/api/v1/support/tickets/' + ticketId + '/messages', { body: 'k6' }, data.s), [200, 201], 'add-msg'));
        group('Support: admin',   () => ok(api('GET', '/api/v1/admin/support/tickets?status=OPEN', null, data.a), 200, 'admin-support'));
        group('Support: close',   () => ok(api('POST', '/api/v1/support/tickets/' + ticketId + '/close', null, data.s), 200, 'close'));
    }
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 7: Admin ───────────────────────────────────────────────────
    group('Admin: stats',   () => ok(api('GET', '/api/v1/admin/stats', null, data.a), 200, 'stats'));
    group('Admin: users',   () => ok(api('GET', '/api/v1/admin/users?page=0&size=10', null, data.a), 200, 'users'));
    group('Admin: courses', () => ok(api('GET', '/api/v1/admin/courses?status=PENDING', null, data.a), 200, 'courses'));
    group('Admin: tutors',  () => ok(api('GET', '/api/v1/admin/tutors?status=PENDING', null, data.a), 200, 'tutors'));
    group('Admin: reviews', () => ok(api('GET', '/api/v1/admin/reviews', null, data.a), 200, 'reviews'));
    group('Admin: notifications',  () => ok(api('GET', '/api/v1/notifications', null, data.a), 200, 'notifications'));
    group('Admin: unread',         () => ok(api('GET', '/api/v1/notifications/unread-count', null, data.a), 200, 'unread'));
    sleep(randomIntBetween(0, 1));

    // ──── PHASE 8: Messaging ──────────────────────────────────────────────
    group('Msg: student convs', () => ok(api('GET', '/api/v1/messages/conversations', null, data.s), 200, 'student-convs'));
    group('Msg: tutor convs', () => {
        const r = api('GET', '/api/v1/messages/conversations', null, data.t);
        ok(r, 200, 'tutor-convs');
        if (r.status === 200) {
            const convs = r.json();
            if (Array.isArray(convs) && convs.length > 0) {
                ok(api('GET', '/api/v1/messages/conversations/' + convs[0].id, null, data.t), 200, 'thread');
            }
        }
    });
    sleep(randomIntBetween(0, 1));
}

// ── Options ────────────────────────────────────────────────────────────────────
export const options = {
    scenarios: {
        // 2 phases: warmup (ramp VUs) → steady state
        load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '5s', target: 10 },   // ramp up
                { duration: '25s', target: 10 },   // steady 10 VUs
                { duration: '5s', target: 0 },     // ramp down
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        http_req_failed:   ['rate<0.10'],
        api_latency:       ['p(99)<5000'],
    },
};
