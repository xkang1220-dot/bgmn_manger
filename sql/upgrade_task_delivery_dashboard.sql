-- 仅在独立任务驾驶舱数据库执行。
ALTER TABLE pm_task
    ADD COLUMN started_at DATETIME DEFAULT NULL COMMENT '实际开始时间' AFTER progress,
    ADD COLUMN completed_at DATETIME DEFAULT NULL COMMENT '实际完成时间' AFTER started_at,
    ADD COLUMN last_activity_at DATETIME DEFAULT NULL COMMENT '最后有效推进时间' AFTER completed_at,
    ADD COLUMN estimated_hours DECIMAL(8,2) DEFAULT NULL COMMENT '预计工时' AFTER last_activity_at,
    ADD COLUMN blocked TINYINT DEFAULT 0 COMMENT '是否阻塞' AFTER estimated_hours,
    ADD COLUMN blocked_reason VARCHAR(500) DEFAULT NULL COMMENT '阻塞原因' AFTER blocked,
    ADD COLUMN risk_level VARCHAR(16) DEFAULT 'NORMAL' COMMENT '人工风险等级 NORMAL/WARNING/DANGER' AFTER blocked_reason,
    ADD KEY idx_task_delivery_status_due (status, due_date),
    ADD KEY idx_task_delivery_owner (assignee_id, status),
    ADD KEY idx_task_delivery_activity (last_activity_at);

UPDATE pm_task
SET last_activity_at = COALESCE(update_time, create_time),
    started_at = CASE WHEN status IN (1, 2, 4) THEN COALESCE(update_time, create_time) ELSE NULL END,
    completed_at = CASE WHEN status = 2 THEN update_time ELSE NULL END,
    blocked = 0,
    risk_level = 'NORMAL'
WHERE last_activity_at IS NULL;

-- 历史任务没有唯一主责人时，优先取第一个任务参与人。
UPDATE pm_task t
JOIN (
    SELECT task_id, MIN(user_id) AS owner_id
    FROM pm_task_member
    GROUP BY task_id
) m ON m.task_id = t.id
SET t.assignee_id = m.owner_id
WHERE t.assignee_id IS NULL;
