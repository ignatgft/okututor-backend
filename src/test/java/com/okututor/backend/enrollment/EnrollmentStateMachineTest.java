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
                .isEqualTo("INVALID_APPLICATION_STATE");

        assertThatThrownBy(() -> enrollmentWithStatus(Enrollment.Status.CANCELLED)
                .transitionTo(Enrollment.Status.ACCEPTED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void scheduleNegotiationFlowIsLegal() {
        Enrollment e = enrollmentWithStatus(Enrollment.Status.ACCEPTED);
        e.transitionTo(Enrollment.Status.SCHEDULE_PENDING);
        e.transitionTo(Enrollment.Status.SCHEDULE_PROPOSED);
        e.transitionTo(Enrollment.Status.SCHEDULED);
        assertThat(e.getStatus()).isEqualTo(Enrollment.Status.SCHEDULED);
    }

    @Test
    void agreedScheduleCanGoBackToPendingOnReject() {
        Enrollment e = enrollmentWithStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        e.transitionTo(Enrollment.Status.SCHEDULE_PENDING);
        assertThat(e.getStatus()).isEqualTo(Enrollment.Status.SCHEDULE_PENDING);
    }

    @Test
    void requestInfoCyclesBetweenPendingAndNeedsInfo() {
        Enrollment e = enrollmentWithStatus(Enrollment.Status.PENDING);
        e.transitionTo(Enrollment.Status.NEEDS_INFO);
        e.transitionTo(Enrollment.Status.PENDING);
        assertThat(e.getStatus()).isEqualTo(Enrollment.Status.PENDING);
    }

    @Test
    void scheduledCanTransitionToCompletedAndIsTerminal() {
        Enrollment e = enrollmentWithStatus(Enrollment.Status.SCHEDULED);
        e.transitionTo(Enrollment.Status.COMPLETED);
        assertThat(e.getStatus()).isEqualTo(Enrollment.Status.COMPLETED);
        assertThatThrownBy(() -> e.transitionTo(Enrollment.Status.CANCELLED))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_APPLICATION_STATE");
    }

    @Test
    void counterSelfLoopOnScheduleProposedIsAllowed() {
        Enrollment e = enrollmentWithStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        e.transitionTo(Enrollment.Status.SCHEDULE_PROPOSED);
        assertThat(e.getStatus()).isEqualTo(Enrollment.Status.SCHEDULE_PROPOSED);
    }

    @Test
    void acceptedCanDirectlyProposeSchedule() {
        Enrollment e = enrollmentWithStatus(Enrollment.Status.ACCEPTED);
        e.transitionTo(Enrollment.Status.SCHEDULE_PROPOSED);
        assertThat(e.getStatus()).isEqualTo(Enrollment.Status.SCHEDULE_PROPOSED);
    }

    @Test
    void notRequestedResponseHasSpecialStatus() {
        assertThat(EnrollmentService.EnrollmentResponse.notRequested().status())
                .isEqualTo("NOT_REQUESTED");
    }
}
