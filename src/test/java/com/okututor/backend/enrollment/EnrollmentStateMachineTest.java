package com.okututor.backend.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.error.ApiException;
import org.junit.jupiter.api.Test;

class EnrollmentStateMachineTest {

    private Enrollment enrollmentWithStatus(Enrollment.Status status) {
        Enrollment e = new Enrollment();
        e.setStatus(status);
        return e;
    }

    @Test
    void pendingCanTransitionToAcceptedAndRejected() {
        Enrollment pending = enrollmentWithStatus(Enrollment.Status.PENDING);

        pending.transitionTo(Enrollment.Status.ACCEPTED);
        assertThat(pending.getStatus()).isEqualTo(Enrollment.Status.ACCEPTED);
    }

    @Test
    void nonPendingCannotTransition() {
        assertThatThrownBy(() -> enrollmentWithStatus(Enrollment.Status.ACCEPTED)
                .transitionTo(Enrollment.Status.REJECTED))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CONFLICT");

        assertThatThrownBy(() -> enrollmentWithStatus(Enrollment.Status.CANCELLED)
                .transitionTo(Enrollment.Status.ACCEPTED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void notRequestedResponseHasSpecialStatus() {
        assertThat(EnrollmentService.EnrollmentResponse.notRequested().status())
                .isEqualTo("NOT_REQUESTED");
    }
}
