package com.petrolpump.credit.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically moves unanswered bills to NO_RESPONSE. Safe to run on several instances (SKIP LOCKED). */
@Component
public class ApprovalTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutJob.class);

    private final ApprovalService approvals;

    public ApprovalTimeoutJob(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @Scheduled(initialDelayString = "${app.jobs.approval-timeout-initial-ms:30000}",
               fixedDelayString = "${app.jobs.approval-timeout-ms:900000}")
    public void run() {
        int n = approvals.markNoResponse();
        if (n > 0) log.info("Marked {} bill(s) as NO_RESPONSE", n);
    }
}
