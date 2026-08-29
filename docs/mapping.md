# Frontend → Backend API Mapping

Источник правды: `front_okututor` (`src/api/endpoints.js`, `src/api/*.api.js`, `src/api/auth.js`,
`src/api/client/*`, `src/pages/Pg*.jsx`, `src/constants/roles.js`, `src/api/mockData.js`).

Глобальные соглашения:

- Base path: `/api/v1`
- JSON: **snake_case** (Jackson `SNAKE_CASE` глобально), пагинация:
  `{ content, page, size, total_elements, total_pages, first, last }`, query: `page`, `size`
- ID: UUID-строки (кроме support ticket id — человекочитаемый `TK-<number>`)
- Ошибки: `{ status, message, error, errors, traceId }`; `error` = код
  (`VALIDATION_ERROR|UNAUTHORIZED|FORBIDDEN|NOT_FOUND|CONFLICT|RATE_LIMITED|...`)
- Время: ISO-8601 UTC (`2026-08-20T10:00:00Z`)
- Auth: `Authorization: Bearer <access>`; refresh/logout — **body** `{ refresh_token }` без Bearer

| # | Frontend page / module | API function | Method | Endpoint | Request DTO (snake_case) | Response DTO (snake_case) | Permission | Service | Entity | Table | Test |
|---|------------------------|--------------|--------|----------|--------------------------|---------------------------|------------|---------|--------|-------|------|
| 1 | AuthModal (`Auth.jsx`) → `auth.js` | login | POST | `/auth/login` | LoginRequest `{email,password}` | 200 TokenPairResponse `{access_token,refresh_token,user}`; **EMAIL_NOT_VERIFIED → 200** `{status:"EMAIL_NOT_VERIFIED",email,error}`; 401 invalid creds | public | AuthService | User, RefreshToken | users, refresh_tokens | AuthServiceTest, AuthControllerWebTest |
| 2 | RegisterModal (`Register.jsx`) | register | POST | `/auth/register` | RegisterRequest `{email,password,repeat_password,full_name,role?}` | 200 `{status:"EMAIL_VERIFICATION_REQUIRED",email}` (или legacy tokens+user) | public | AuthService | User, EmailCode | users, email_codes | AuthServiceTest |
| 3 | httpClient/refreshManager | refresh | POST | `/auth/refresh` | `{refresh_token}` | 200 `{access_token,refresh_token}`; 401 → фронт чистит токены | public (rate limited) | RefreshTokenService | RefreshToken | refresh_tokens | RefreshRotationTest |
| 4 | authStore.logout | logout | POST | `/auth/logout` | `{refresh_token}` | 204 | public | RefreshTokenService | RefreshToken | refresh_tokens | — |
| 5 | authStore.init / auth.api.me | me (light) | GET | `/auth/me` | — | UserResponse | authenticated | UserService | User | users | — |
| 6 | PgVerifyEmail | verifyEmail | POST | `/auth/verify-email` | `{email,code}` | 200 `{status:"EMAIL_VERIFIED",access_token,refresh_token,user}`; ошибки в `error`: `INVALID_CODE`,`VERIFICATION_CODE_EXPIRED`,`TOO_MANY_ATTEMPTS`; 429 `RATE_LIMITED` | public | EmailCodeService | EmailCode | email_codes | EmailCodeServiceTest |
| 7 | PgVerifyEmail | resendVerification | POST | `/auth/resend-verification` | `{email}` | `{status:"VERIFICATION_CODE_SENT",expires_in,resend_available_in}` (+camelCase дубль ключей на переходный период) | public (rate limited) | EmailCodeService | EmailCode | email_codes | EmailCodeServiceTest |
| 8 | PgForgotPassword | forgotPassword | POST | `/auth/forgot-password` | `{email}` | всегда `{status:"PASSWORD_RESET_EMAIL_SENT"}` (anti-enumeration) | public (rate limited) | PasswordResetService | EmailCode | email_codes | PasswordResetTest |
| 9 | PgForgotPassword | verifyResetCode | POST | `/auth/verify-reset-code` | `{email,code}` | `{status:"RESET_CODE_VERIFIED"}`; коды ошибок как в #6 | public | PasswordResetService | EmailCode | email_codes | PasswordResetTest |
| 10 | PgResetPassword | resetPassword | POST | `/auth/reset-password` | `{email,code,password}` | `{status:"PASSWORD_RESET"}` | public | PasswordResetService | User, EmailCode | users, email_codes | PasswordResetTest |
| 11 | PgSettings (change email) | changeEmail | POST | `/auth/change-email` | `{email}` (auth) | `{status:"VERIFICATION_CODE_SENT",email}` | authenticated | EmailCodeService | EmailCode | email_codes | — |
| 12 | PgOAuthCallback + Google btn | oauth2 google | GET | `/oauth2/authorization/google?role=...` → callback | — | 302 `${FRONTEND}/oauth/callback?access_token=..&refresh_token=..` или `?error=` | public | OAuthLoginSuccessHandler | User | users | — |
| 13 | Profile.jsx / ProtectedRoute | getCurrentUser | GET | `/users/me` | — | UserResponse `{id,email,full_name,first_name,last_name,role,avatar,verified,created_at}` | authenticated | UserService | User | users | UserControllerWebTest |
| 14 | Profile.jsx save | updateMe | PUT | `/users/me` | UpdateProfileRequest `{full_name?,first_name?,last_name?,bio?,phone?,location?}` | UserResponse | authenticated | UserService | User | users | — |
| 15 | Profile.jsx avatar | updateAvatar | PUT multipart | `/users/me/avatar` | form field **file** | `{avatar:url}` | authenticated | FileStorage | User | users | — |
| 16 | PgTutorProfile / PopTutor | user byId | GET | `/users/{id}` | — | PublicUserResponse | public | UserService | User | users | — |
| 17 | PgStudentTutors | tutors list | GET | `/users/tutors` | query `page,size,q` | Page\<TutorCardResponse\> `{id,full_name,avatar,subjects,average_rating,price_per_hour}` | public | TutorService | TutorProfile | tutor_profiles | — |
| 18 | PgTutorProfile | tutor byId | GET | `/tutors/{id}` | — | TutorDetailResponse | public | TutorService | TutorProfile | tutor_profiles | — |
| 19 | PgBecomeTutor | submitApplication | POST | `/tutors/applications` | TutorApplicationRequest `{full_name,phone,location,experience_years,experience_description,education,subjects(csv),languages(csv),bio,id_document_name}` | TutorApplicationResponse `{id,status:"PENDING",created_at}` | TUTOR/STUDENT | TutorApplicationService | TutorApplication | tutor_applications | — |
| 20 | PgTutorApplication | myApplication | GET | `/tutors/applications/me` | — | TutorApplicationResponse \| `{status:"NOT_REQUESTED"}` | authenticated | TutorApplicationService | TutorApplication | tutor_applications | — |
| 21 | PgSchedule | availability CRUD | GET/POST/DELETE | `/availability`, `/availability/{id}` | AvailabilityRequest `{weekday,start_time,end_time}` | `[AvailabilityResponse]` | TUTOR | AvailabilityService | AvailabilitySlot | availability_slots | — |
| 22 | Search/Home | courses list | GET | `/courses?q&subject&location_type&group_size&max_price&price_min&rating_min&page&size` | — | Page\<CourseResponse\> (фильтры в PostgreSQL; `rating_min` 1..5) | public| CourseService | Course | courses | CourseControllerWebTest |
| 23 | HomeSection | popular | GET | `/courses/popular` | — | `[CourseResponse]` (top 8) | public | CourseService | Course | courses | — |
| 24 | PgCourseView | course byId | GET | `/courses/{id}` | — | CourseResponse `{id,title,subject,description,price_per_hour,currency,average_rating,teacher_id,teacher_name,location_type,group_size,days,specific_days,experience,max_students,status,created_at}` | public | CourseService | Course | courses | — |
| 25 | CourseWizard create | create | POST | `/courses` | CourseUpsertRequest `{title,description,subject,category,days[],specific_days,group_size,location_type,experience,price_per_hour,currency,max_students,status}` | CourseResponse (+`id`) | TUTOR | CourseService | Course | courses | — |
| 26 | CourseWizard edit | update | PUT | `/courses/{id}` | CourseUpsertRequest | CourseResponse | TUTOR (owner) | CourseService | Course | courses | — |
| 27 | PgTutorCourses/CourseView | delete | DELETE | `/courses/{id}` | — | 204 | TUTOR owner / ADMIN | CourseService | Course | courses | — |
| 28 | PgTutorCourses | byTeacher | GET | `/courses/teacher/{teacherId}` | — | Page\<CourseResponse\> | public | CourseService | Course | courses | — |
| 29 | CourseView reviews | reviews list/create | GET/POST | `/courses/{courseId}/reviews` | ReviewRequest `{rating(1..5),comment}` | `[ReviewResponse{id,rating,comment,student_id,student_name,created_at}]` / ReviewResponse | public list; STUDENT create (completed booking required, иначе 403 REVIEW_NOT_ALLOWED) | ReviewService | Review | reviews | ReviewServiceTest |
| 30 | ReviewModal | review for booking | POST | `/courses/{courseId}/reviews/booking/{bookingId}` | `{rating,comment}` | ReviewResponse | STUDENT участник COMPLETED booking | ReviewService | Review, Booking | reviews, bookings | ReviewServiceTest |
| 31 | CourseView enroll | enroll | POST | `/courses/{courseId}/enroll` | EnrollRequest `{message?,preferred_schedule?}` | EnrollmentResponse `{id,status:"PENDING",course_id,student_id,...}`; 409 duplicate PENDING/ACCEPTED | STUDENT | EnrollmentService | Enrollment | enrollments | EnrollmentServiceTest |
| 32 | PgStudentCourses | my enrollments | GET | `/students/me/enrollments` | — | Page\<EnrollmentResponse\> | STUDENT | EnrollmentService | Enrollment | enrollments | — |
| 33 | CourseView cancel | delete enrollment | DELETE | `/enrollments/{id}` | — | 204 | STUDENT owner | EnrollmentService | Enrollment | enrollments | — |
| 34 | PgTutorDashboard requests | tutor requests | GET | `/tutors/me/requests` | — | Page\<EnrollmentResponse\> | TUTOR (owner курса) | EnrollmentService | Enrollment | enrollments | — |
| 35 | PgStudentRequests | accept/reject | POST | `/enrollments/{id}/accept` , `/reject` , `/accept-and-schedule` | accept/reject: `—`; accept-and-schedule: `{date(yyyy-MM-dd),time(HH:mm),duration_minutes(30|45|60|90|120),timezone?(IANA, default UTC)}` | EnrollmentResponse; state machine PENDING→ACCEPTED/REJECTED/SCHEDULED; accept-and-schedule атомарно переводит PENDING→ACCEPTED + создаёт DIRECT conversation + CONFIRMED Booking; reject учебного запроса требует `{reason}` | TUTOR owner | EnrollmentService | Enrollment | enrollments | EnrollmentStateMachineTest, AcceptAndScheduleTzTest |
| 36 | CourseView status | enrollment for course | GET | `/courses/{courseId}/enrollment` | — | EnrollmentResponse или `{status:"NOT_REQUESTED"}` | authenticated | EnrollmentService | Enrollment | enrollments | — |
| 36a | — | enrollment byId | GET | `/enrollments/{id}` | — | EnrollmentResponse | участник (student/tutor) / ADMIN | EnrollmentService | Enrollment | enrollments | — |
| 37 | CourseView book lesson | create booking | POST | `/bookings` | BookingCreateRequest `{course_id,enrollment_id,date(yyyy-MM-dd),time(HH:mm),duration_minutes,timezone?(IANA, default UTC)}` | BookingResponse `{id,course_id,course_title,status:"PENDING",date,start_at,end_at,local_start,timezone,student_id,teacher_id,duration_minutes}`; 409 double-booking; 422 bad date/duration; requires ACCEPTED enrollment → 403 NOT_ELIGIBLE | STUDENT (accepted enrollment) | BookingService | Booking | bookings | BookingConcurrencyTest |
| 38 | Dashboard/TutorDashboard | booking byId | GET | `/bookings/{id}` | — | BookingResponse | участник / ADMIN | BookingService | Booking | bookings | — |
| 39 | TutorDashboard | confirm/reject/cancel/complete | POST | `/bookings/{id}/confirm` `/reject` `/cancel` `/complete` | — | BookingResponse; машина состояний PENDING→CONFIRMED/REJECTED/CANCELLED, CONFIRMED→COMPLETED/CANCELLED; иначе 409 | confirm/reject/complete: TUTOR; cancel: участник | BookingService | Booking | bookings | BookingStateMachineTest |
| 40 | Dashboard student | my bookings | GET | `/bookings/me` | — | Page\<BookingResponse\> | authenticated | BookingService | Booking | bookings | — |
| 41 | TutorDashboard | teacher bookings | GET | `/bookings/teacher` | — | Page\<BookingResponse\> | TUTOR | BookingService | Booking | bookings | — |
| 42 | PgLesson join | meeting token | POST | `/bookings/{bookingId}/meeting/token` | — | `{server_url:"wss://...",token:"..."}`; только участник booking → 403; вне временного окна → 403 `MEETING_NOT_AVAILABLE` | участник booking | LiveKitTokenService, MeetingService | MeetingSession | meeting_sessions | MeetingTokenSecurityTest, MeetingTokenWindowTest |
| 43 | PgLesson leave | meeting end | POST | `/bookings/{bookingId}/meeting/end` | — | 200 `{status:"ENDED"}` | участник booking | MeetingService | MeetingSession | meeting_sessions | — |
| 44 | PgLessons/Schedule | lessons list | GET | `/lessons` | — | Page\<LessonResponse `{id,title,counterpart,start_at,status,joinable,booking_id}`\> | authenticated | LessonService | Lesson | lessons | — |
| 45 | Tutor lessons | create/start/complete/cancel | POST | `/lessons`, `/lessons/{id}/start|complete|cancel` | LessonCreateRequest | LessonResponse | TUTOR/ADMIN | LessonService | Lesson | lessons | LessonStateMachineTest |
| 45a | Calendar (Dashboard/Schedule) | calendar | GET | `/calendar?from&to&timezone?` | — | `[CalendarItem{id,type:BOOKING\|STANDALONE_LESSON,course_id,course_title,booking_id,lesson_id,counterpart,start_at,end_at,status,joinable,cancelled,local_start,local_end,timezone}]`; from=to ISO-8601 instant, диапазон ≤ 90 суток; STUDENT→свои, TUTOR→свои, ADMIN/SUPER_ADMIN→все | authenticated (role-scoped) | CalendarService | Booking, Lesson | bookings, lessons | CalendarServiceTest, CalendarControllerWebTest |
| 46 | PgMessages | conversations | GET | `/messages/conversations` | — | `[ConversationResponse {id,type:DIRECT|SUPPORT|SYSTEM,counterpart_name,unread_count,last_message,updated_at}]` | authenticated | MessagingService | Conversation | conversations | — |
| 46a | — | open conversation | POST | `/messages/conversations` | OpenConversationRequest `{user_id,type:"DIRECT"}` | ConversationResponse; создаёт/возвращает DIRECT conversation (idempotent); admin может открыть DIRECT с любым; student/tutor — только после ACCEPTED enrollment или APPROVED tutor application | authenticated (authorized pair) | MessagingService | Conversation | conversations | AdminTutorChatAccessIT |
| 47 | PgMessages thread | conversation messages | GET | `/messages/conversations/{id}` | — | `[MessageResponse {id,sender_id,sender_name,body,created_at,read_at}]` | участник | MessagingService | Message | messages | — |
| 48 | PgMessages send | send | POST | `/messages` | `{conversation_id,body}` | MessageResponse | участник DIRECT | MessagingService | Message, Notification | messages | — |
| 49 | Navbar/PgNotifications | notifications | GET | `/notifications` | — | `[NotificationResponse {id,message,type,read,link,payload,created_at}]`; `payload` — структурированный JSONB контекст (напр. `{booking_id}`, `{enrollment_id}`, `{course_id}`) | authenticated | NotificationService | Notification | notifications | — |
| 50 | Navbar badge | unread count | GET | `/notifications/unread-count` | — | `{count:N}` | authenticated | NotificationService | Notification | notifications | — |
| 51 | PgNotifications | mark read/all | POST | `/notifications/{id}/read`, `/notifications/read-all` | — | 204 | authenticated | NotificationService | Notification | notifications | — |
| 52 | PgSupport/New/Ticket | tickets CRUD | POST/GET | `/support/tickets` | TicketCreateRequest `{category,subject,description,priority}` → `{id:"TK-n",status:"OPEN",...}`; GET список с фильтрами | TicketResponse (см. mockData: last_message_preview, unread_count, assigned_admin_*) | authenticated | SupportTicketService | SupportTicket | support_tickets | SupportServiceTest |
| 53 | PgSupportTicket | messages | GET/POST | `/support/tickets/{id}/messages` | `{body}` | `[SupportMessageResponse {id:"msg-…",ticket_id,sender_id,sender_name,sender_role,body,created_at,type:"USER_VISIBLE",attachments,client_status:"SENT"}]` | author / admin | SupportMessageService | SupportTicketMessage | support_ticket_messages | — |
| 54 | PgSupportTicket | read/close/reopen | POST | `/support/tickets/{id}/read|close|reopen` | — | TicketResponse; статусы OPEN/IN_PROGRESS/WAITING_FOR_USER/RESOLVED/CLOSED | author / admin | SupportTicketService | SupportTicket | support_tickets | — |
| 55 | PgAdminSupport | admin tickets | GET | `/admin/support/tickets?status&priority&q` | — | Page\<AdminTicketResponse\> | ADMIN+ | SupportTicketService | SupportTicket | support_tickets | — |
| 56 | PgAdminSupport(Ticket) | assign/take/status/priority | POST `/assign {admin_id?}`, POST `/take`, **PUT** `/status {status}`, **PUT** `/priority {priority}` | | AdminTicketResponse | ADMIN+ | SupportTicketService | SupportTicket | support_tickets | — |
| 57 | PgAdminSupport | agents | GET | `/admin/support/agents` | — | `[{id,full_name,email}]` | ADMIN+ | SupportTicketService | User | users | — |
| 58 | PgAdminUsers | users list | GET | `/admin/users?q&role&status&page&size` | — | Page\<AdminUserResponse\> | ADMIN+ | AdminUserService | User | users | — |
| 59 | PgAdminUsers | block/unblock | **PUT** | `/admin/users/{id}/block` `/unblock` | — | AdminUserResponse; audit | ADMIN+ | AdminUserService | User, AuditLog | audit_logs | RbacTest |
| 60 | PgAdminUsers | role change | **PUT** | `/admin/users/{id}/role` | `{role}` | AdminUserResponse; SUPER_ADMIN only для выдачи админ-ролей | SUPER_ADMIN (ADMIN→STUDENT/TUTOR) | AdminUserService | User, AuditLog | audit_logs | RbacTest |
| 61 | PgAdminUsers | verify | **PUT** | `/admin/users/{id}/verify` | — | AdminUserResponse | ADMIN+ | AdminUserService | User, AuditLog | audit_logs | — |
| 62 | PgAdmin | stats | GET | `/admin/stats` | — | `{total_users,total_courses,total_reviews,total_bookings,pending_tutor_applications,pending_enrollments}` | ADMIN+ | AdminStatsService | — | — | — |
| 62a | PgStudentStats | my stats | GET | `/students/me/stats` | — | `{total_enrollments,pending_enrollments,accepted_enrollments,completed_bookings,total_spent}`; нулевые значения вместо 404/500 | STUDENT | StatsService | — | — | StatsEmptyTest |
| 62b | PgTutorStats | my stats | GET | `/tutors/me/stats` | — | `{total_courses,active_courses,total_requests,pending_requests,accepted_requests,completed_bookings,cancelled_bookings,total_lessons,total_revenue,active_students,average_rating}` | TUTOR | StatsService | — | — | StatsEmptyTest |
| 63 | PgAdminTutors | applications | GET | `/admin/tutors?status=PENDING` | — | Page\<AdminTutorApplicationResponse\> | ADMIN+ | TutorApplicationService | TutorApplication | tutor_applications | — |
| 64 | PgAdminTutors | approve/reject | POST | `/admin/tutors/{id}/approve` ; `/reject {reason}` | ReasonRequest `{reason}` (обязателен непустой для reject) | application response; approve повышает пользователя до TUTOR, уведомление TUTOR_APPLICATION_APPROVED, повторный approve/reject → 409 CONFLICT | ADMIN+ | TutorApplicationService, AuditLog | TutorApplication | tutor_applications, audit_logs | — |
| 64a | PgAdminTutors | detail | GET | `/admin/tutors/{id}` | — | AdminTutorApplicationDetail `{id,status,user_id,email,full_name,avatar,created_at,reason,subjects,...}` | ADMIN+ | TutorApplicationService | TutorApplication | tutor_applications | — |
| 65 | PgAdminCourses | moderate | GET `/admin/courses?status`; POST `/admin/courses/{id}/approve`; POST `/admin/courses/{id}/reject {reason}` | — | CourseResponse | ADMIN+ | CourseModerationService, AuditLog | Course | courses, audit_logs | — |
| 66 | PgAdminReviews | hide/restore | GET `/admin/reviews`; POST `/admin/reviews/{id}/hide`; POST `/{id}/restore` | — | AdminReviewResponse | ADMIN+ | ReviewModerationService, AuditLog | Review | reviews, audit_logs | — |
| 67 | PgAdminReports | reports | GET `/admin/reports`; **PUT** `/admin/reports/{id}` `{status}` | — | `[ReportResponse]` | ADMIN+ | ReportService, AuditLog | Report | reports | — |

## Особые случаи и адаптеры

1. **EMAIL_NOT_VERIFIED** при login возвращается с HTTP **200** (фронт проверяет `data.status`,
   а не HTTP-код): `{status:"EMAIL_NOT_VERIFIED", email, error:"EMAIL_NOT_VERIFIED"}`.
2. **Register** требует `repeat_password` и валидирует равенство паролей (422 `VALIDATION_ERROR`).
3. **Meeting token** привязан к booking (не lesson); ответ строго `server_url` + `token`;
   room = `booking-{uuid}`; identity = user id; TTL 30 мин.
4. **Refresh rotation**: rotate-on-use с grace-period 30 c для конкурентных запросов;
   повторное использование «старого» токена вне grace → ревок всей token family.
5. **Admin block/unblock/role/verify** — метод **PUT** (не POST!), как вызывает `admin.api.js`.
6. **Admin support status/priority** — тоже **PUT** с body `{status}` / `{priority}`.
7. **Support ticket id** — строка `TK-<n>`; message id — строка `msg-<uuid8>`;
   статус ожидания — **WAITING_FOR_USER**.
8. **Resend verification** отдаёт и snake_case (`expires_in`), и camelCase (`expiresIn`) ключи.
9. **Reports** создаются seed'ом (во фронте нет user-facing endpoint создания репорта) — задел.
10. **Booking/acceptAndSchedule date/time**: UI шлёт отдельно `date` (yyyy-MM-dd) и `time`
    (HH:mm|HH:mm:ss|ISO instant) + опционально `timezone` (IANA, default UTC). Бэкенд
    комбинирует в Instant (UTC), наружу отдаёт `date` = ISO-8601 instant, плюс `local_start`
    (локальное время в часовом поясе пользователя) и `timezone`. Длительность — из набора
    {30,45,60,90,120} мин, иначе 422 `VALIDATION_ERROR`.
14. **Новые error-коды**: встречи вне временного окна → 403 `MEETING_NOT_AVAILABLE`
    (вместо 409); отзыв на не-COMPLETED booking → 403 `REVIEW_NOT_ALLOWED`; book без
    ACCEPTED enrollment → 403 `NOT_ELIGIBLE`.
15. **Notifications payload**: `NotificationResponse` включает структурированный `payload`
    (JSONB, path `notifications.payload`), напр. `{booking_id}`, `{enrollment_id}`, `{course_id}`,
    чтобы фронт строил ссылки. Типы (константы `NotificationType`): `COURSE_APPLICATION`,
    `APPLICATION_ACCEPTED`, `APPLICATION_REJECTED`, `APPLICATION_CANCELLED`,
    `BOOKING_CONFIRMED`, `BOOKING_REJECTED`, `BOOKING_CANCELLED`, `BOOKING_COMPLETED`,
    `TUTOR_APPLICATION_APPROVED`, `TUTOR_APPLICATION_REJECTED`, `MESSAGE_RECEIVED`, ...
16. **OAuth callback** редиректит на `${app.frontend.url}/oauth/callback?access_token=…&refresh_token=…`
    (query string — так ждёт `PgOAuthCallback` через `searchParams`). Задокументировано в OpenAPI.
17. `/admin/admins` во фронте не вызывается — реализовано как задел (SUPER_ADMIN).
18. Фронтовый nginx проксирует `/api/ → backend:8080/api/` ⇒ сервис compose называется **backend**,
    healthcheck — `GET /actuator/health`.
