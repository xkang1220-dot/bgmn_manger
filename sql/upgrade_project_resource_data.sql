USE kk_manager_bgmn_doc_v1;

CREATE TABLE IF NOT EXISTS pm_project_link (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id  BIGINT       NOT NULL,
    type        VARCHAR(16)  NOT NULL COMMENT 'REPOSITORY/WEBSITE',
    title       VARCHAR(128) NOT NULL,
    url         VARCHAR(1000) NOT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT       DEFAULT NULL,
    update_by   BIGINT       DEFAULT NULL,
    deleted     TINYINT      DEFAULT 0,
    KEY idx_project_type (project_id, type)
) COMMENT='项目仓库及网站链接';

INSERT INTO pm_project_link (project_id, type, title, url)
SELECT id, 'REPOSITORY', '代码仓库', repository_url
FROM pm_project p
WHERE p.deleted = 0 AND p.repository_url IS NOT NULL AND p.repository_url <> ''
  AND NOT EXISTS (SELECT 1 FROM pm_project_link l WHERE l.project_id = p.id AND l.type = 'REPOSITORY' AND l.deleted = 0);

INSERT INTO pm_project_link (project_id, type, title, url)
SELECT id, 'WEBSITE', '项目网站', website_url
FROM pm_project p
WHERE p.deleted = 0 AND p.website_url IS NOT NULL AND p.website_url <> ''
  AND NOT EXISTS (SELECT 1 FROM pm_project_link l WHERE l.project_id = p.id AND l.type = 'WEBSITE' AND l.deleted = 0);
