package com.okututor.backend.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.error.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** date+time+timezone → ожидаемый Instant (контракт A.1/A.2 + F.3/F.4). */
class AcceptAndScheduleTzTest {

    @Test
    void noTimezoneDefaultsToUtc() {
        Instant instant = ScheduleParser.combine("2026-08-28", "18:00", null);
        assertThat(instant.toString()).isEqualTo("2026-08-28T18:00:00Z");
    }

    @Test
    void timezoneShiftsToUtc() {
        // Asia/Bishkek = UTC+6 без летнего времени
        Instant instant = ScheduleParser.combine("2026-08-28", "18:00", "Asia/Bishkek");
        assertThat(instant.toString()).isEqualTo("2026-08-28T12:00:00Z");
    }

    @Test
    void acceptsHhMmAndHhMmSs() {
        assertThat(ScheduleParser.combine("2026-08-28", "10:00", null))
                .isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));
        assertThat(ScheduleParser.combine("2026-08-28", "10:30:00", null))
                .isEqualTo(Instant.parse("2026-08-28T10:30:00Z"));
    }

    @Test
    void rejectsWrongTimeFormat() {
        assertThatThrownBy(() -> ScheduleParser.combine("2026-08-28", "9:0", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rejectsUnknownTimezone() {
        assertThatThrownBy(() -> ScheduleParser.combine("2026-08-28", "10:00", "Mars/Olympus"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void durationMustBeFromAllowedSet() {
        assertThatThrownBy(() -> ScheduleParser.requireDuration(75))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VALIDATION_ERROR");
    }
}
