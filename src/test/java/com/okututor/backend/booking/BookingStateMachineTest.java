package com.okututor.backend.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.error.ApiException;
import org.junit.jupiter.api.Test;

class BookingStateMachineTest {

    private Booking bookingWithStatus(Booking.Status status) {
        Booking b = new Booking();
        b.setStatus(status);
        return b;
    }

    @Test
    void pendingToConfirmedAllowed() {
        Booking booking = bookingWithStatus(Booking.Status.PENDING);
        booking.transitionTo(Booking.Status.CONFIRMED);
        assertThat(booking.getStatus()).isEqualTo(Booking.Status.CONFIRMED);
    }

    @Test
    void confirmedToCompletedAndCancelledOnly() {
        Booking confirmed = bookingWithStatus(Booking.Status.CONFIRMED);

        confirmed.transitionTo(Booking.Status.COMPLETED);
        assertThat(confirmed.getStatus()).isEqualTo(Booking.Status.COMPLETED);

        Booking confirmed2 = bookingWithStatus(Booking.Status.CONFIRMED);
        confirmed2.transitionTo(Booking.Status.CANCELLED);
        assertThat(confirmed2.getStatus()).isEqualTo(Booking.Status.CANCELLED);
    }

    @Test
    void completedIsTerminal() {
        assertThatThrownBy(() -> bookingWithStatus(Booking.Status.COMPLETED)
                .transitionTo(Booking.Status.CANCELLED))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CONFLICT");

        assertThatThrownBy(() -> bookingWithStatus(Booking.Status.REJECTED)
                .transitionTo(Booking.Status.CONFIRMED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    void dateAndTimeAreCombinedIntoInstant() {
        var instant = ScheduleParser.combine("2026-08-20", "10:00", null);
        org.assertj.core.api.Assertions.assertThat(instant.toString())
                .startsWith("2026-08-20T10:00:00Z");

        // без зоны — UTC; с зоной — сдвиг на смещение
        var bishkek = ScheduleParser.combine("2026-08-20", "10:00", "Asia/Bishkek");
        org.assertj.core.api.Assertions.assertThat(bishkek.toString())
                .startsWith("2026-08-20T04:00:00Z");

        assertThatThrownBy(() -> ScheduleParser.combine("bad", "10:00", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VALIDATION_ERROR");

        assertThatThrownBy(() -> ScheduleParser.parseTime("9:0"))
                .isInstanceOf(ApiException.class);
    }
}
