package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies an AI-workflow leave decision to a batch of leave requests, one at a time, reporting
 * what actually happened to each.
 *
 * <p><b>Every mutation still goes through {@link LeaveService#updateLeaveStatus}</b> — this class
 * adds preconditions and reporting, never a second write path. That matters because
 * updateLeaveStatus also audits the change and notifies the student; bypassing it to save a
 * lookup would silently drop both.
 *
 * <p><b>Why the PENDING precondition exists.</b> Approving a batch drafted minutes ago could
 * otherwise apply a decision another admin already made, and would fire a second "your leave has
 * been ..." notification at the student. The gap between drafting a batch and approving the card
 * is exactly where that happens, so each leave is re-read at apply time and only acted on if it
 * is still PENDING. Anything else is skipped and reported — a skip is a correct refusal to
 * overwrite someone else's decision, not a failure.
 *
 * <p>This check and updateLeaveStatus's own locked, same-status guard are complementary, not
 * redundant: this one avoids even attempting an update that's already known to be pointless, and
 * closes the (unlikely but real) gap in between with a pessimistic lock — see
 * updateLeaveStatus's Javadoc and the InvalidLeaveStatusTransitionException catch below.
 */
@Service
public class LeaveDecisionService {

    private static final Logger log = LoggerFactory.getLogger(LeaveDecisionService.class);

    public static final String APPLIED = "applied";
    public static final String SKIPPED_NOT_PENDING = "skipped_not_pending";
    public static final String SKIPPED_NOT_FOUND = "skipped_not_found";
    public static final String SKIPPED_WRONG_CLASS = "skipped_wrong_class";
    public static final String FAILED = "failed";

    /** Outcomes that mean "deliberately left alone", as opposed to "went wrong". */
    public static boolean isSkip(String outcome) {
        return SKIPPED_NOT_PENDING.equals(outcome)
                || SKIPPED_NOT_FOUND.equals(outcome)
                || SKIPPED_WRONG_CLASS.equals(outcome);
    }

    @Autowired private LeaveService leaveService;

    /**
     * @param className when non-null, a leave outside this class is skipped rather than applied —
     *                  the class confinement for a TEACHER-initiated batch, re-checked here and
     *                  not merely at selection time
     * @return leaveId → outcome, in the order given
     */
    public Map<Long, String> applyDecisions(List<Long> leaveIds,
                                            LeaveStatus decision,
                                            String className,
                                            HttpServletRequest request) {
        Map<Long, String> outcomes = new LinkedHashMap<>();

        for (Long leaveId : leaveIds) {
            try {
                // School-scoped by construction — getLeaveById filters on the caller's schoolId.
                Optional<Leave> found = leaveService.getLeaveById(leaveId);
                if (found.isEmpty()) {
                    outcomes.put(leaveId, SKIPPED_NOT_FOUND);
                    continue;
                }
                Leave leave = found.get();

                if (className != null && !className.equals(leave.getClassName())) {
                    log.warn("Leave {} belongs to class {}, outside this batch's class {} — skipped.",
                            leaveId, leave.getClassName(), className);
                    outcomes.put(leaveId, SKIPPED_WRONG_CLASS);
                    continue;
                }

                if (leave.getStatus() != LeaveStatus.PENDING) {
                    log.info("Leave {} is already {} — skipped rather than overwritten.",
                            leaveId, leave.getStatus());
                    outcomes.put(leaveId, SKIPPED_NOT_PENDING);
                    continue;
                }

                leaveService.updateLeaveStatus(leaveId, decision, request);
                outcomes.put(leaveId, APPLIED);

            } catch (InvalidLeaveStatusTransitionException e) {
                // The PENDING check above passed, but lost the race to another decision between
                // then and the locked update inside updateLeaveStatus — same "someone else
                // already decided" outcome as that check, just caught later by the lock instead
                // of earlier by us. Reported the same way, not as a failure.
                log.info("Leave {} was decided concurrently before this decision could apply — skipped.", leaveId);
                outcomes.put(leaveId, SKIPPED_NOT_PENDING);
            } catch (Exception e) {
                log.error("Failed to apply decision to leave {}: {}", leaveId, e.getMessage());
                outcomes.put(leaveId, FAILED);
            }
        }
        return outcomes;
    }
}
