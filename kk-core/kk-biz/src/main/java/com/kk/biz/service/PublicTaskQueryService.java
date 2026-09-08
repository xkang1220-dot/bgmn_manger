package com.kk.biz.service;

import com.kk.biz.dto.PublicTaskItem;
import com.kk.biz.dto.PublicTaskQueryResult;
import com.kk.biz.entity.PmTask;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import com.kk.system.support.TaskQueryRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PublicTaskQueryService {

    private static final String INVALID = "校验码无效";
    private static final Map<Integer, String> STATUS_LABEL = Map.of(
            0, "待办",
            1, "进行中",
            2, "已完成",
            3, "已关闭"
    );

    private final SysUserService userService;
    private final PmTaskService taskService;
    private final TaskQueryRateLimiter rateLimiter;

    @Value("${kk.web.base-url:http://localhost:5173}")
    private String webBaseUrl;

    public PublicTaskQueryResult query(String code, String clientIp) {
        rateLimiter.assertAllowed(clientIp);
        if (!isValidCode(code)) {
            rateLimiter.recordFail(clientIp);
            throw new BusinessException(INVALID);
        }
        SysUser user = userService.getEnabledByTaskQueryCode(code.trim());
        if (user == null) {
            rateLimiter.recordFail(clientIp);
            throw new BusinessException(INVALID);
        }
        rateLimiter.recordSuccess(clientIp);
        PublicTaskQueryResult result = new PublicTaskQueryResult();
        result.setNickname(displayName(user));
        result.setTasks(taskService.listRelatedTasks(user.getId()).stream().map(this::toItem).toList());
        return result;
    }

    private PublicTaskItem toItem(PmTask task) {
        PublicTaskItem item = new PublicTaskItem();
        item.setId(task.getId());
        item.setProjectId(task.getProjectId());
        item.setTitle(task.getTitle());
        item.setStatus(task.getStatus());
        item.setStatusLabel(STATUS_LABEL.getOrDefault(task.getStatus() == null ? 0 : task.getStatus(), "—"));
        item.setPriority(task.getPriority());
        item.setStartDate(task.getStartDate());
        item.setDueDate(task.getDueDate());
        item.setProgress(task.getProgress());
        item.setProjectName(task.getProjectName());
        item.setAssigneeName(null);
        item.setParticipantNames(task.getParticipantNames() == null ? List.of() : task.getParticipantNames());
        item.setOverdue(Boolean.TRUE.equals(task.getOverdue()));
        item.setJumpUrl(buildJumpUrl(task));
        return item;
    }

    private String buildJumpUrl(PmTask task) {
        if (task.getId() == null) {
            return null;
        }
        String base = webBaseUrl == null ? "" : webBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder url = new StringBuilder(base)
                .append("/project/task?taskId=")
                .append(task.getId());
        if (task.getProjectId() != null) {
            url.append("&projectId=").append(task.getProjectId());
        }
        return url.toString();
    }

    private static boolean isValidCode(String code) {
        return StringUtils.hasText(code) && code.trim().matches("\\d{4}");
    }

    private static String displayName(SysUser user) {
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        return user.getUsername();
    }
}
