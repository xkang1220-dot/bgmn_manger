package com.kk.biz.ticket.support;

import com.kk.biz.ticket.dto.TicketUpdateRequest;
import com.kk.biz.ticket.entity.TicketWorkOrder;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

public final class TicketAttributeUpdater {

    private TicketAttributeUpdater() {
    }

    public static void apply(TicketWorkOrder order, TicketUpdateRequest req) {
        if (req == null) {
            return;
        }
        if (StringUtils.hasText(req.getTitle())) {
            TicketValidation.requireTitle(req.getTitle());
            order.setTitle(req.getTitle().trim());
        }
        if (StringUtils.hasText(req.getType())) {
            order.setType(TicketValidation.requireType(req.getType()));
        }
        if (StringUtils.hasText(req.getUrgency())) {
            order.setUrgency(TicketValidation.requireUrgency(req.getUrgency()));
        }
        if (req.getDescription() != null) {
            TicketValidation.requireDescription(req.getDescription());
            order.setDescription(req.getDescription());
        }

        boolean statusExplicit = StringUtils.hasText(req.getStatus());
        if (statusExplicit) {
            order.setStatus(TicketValidation.requireStatus(req.getStatus()));
        }

        if (req.getProgress() != null) {
            int progress = TicketValidation.clampProgress(req.getProgress());
            order.setProgress(progress);
            if (!statusExplicit) {
                deriveStatusFromProgress(order, progress);
            }
        }

        if (Boolean.TRUE.equals(req.getClearExpectedCompleteDate())) {
            order.setExpectedCompleteDate(null);
        } else if (req.getExpectedCompleteDate() != null) {
            order.setExpectedCompleteDate(req.getExpectedCompleteDate());
        }

        if (Boolean.TRUE.equals(req.getClearProject())) {
            order.setProjectId(null);
        } else if (req.getProjectId() != null) {
            order.setProjectId(req.getProjectId());
        }

        markCompletedAtIfNeeded(order);
    }

    public static void deriveStatusFromProgress(TicketWorkOrder order, int progress) {
        if (progress >= 100) {
            order.setStatus("completed");
        } else if (progress > 0 && "pending".equals(order.getStatus())) {
            order.setStatus("in_progress");
        }
    }

    public static void markCompletedAtIfNeeded(TicketWorkOrder order) {
        if (TicketValidation.isCompletedStatus(order.getStatus()) && order.getCompletedAt() == null) {
            order.setCompletedAt(LocalDateTime.now());
        }
    }
}
