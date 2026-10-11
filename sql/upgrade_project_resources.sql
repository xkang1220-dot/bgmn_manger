USE kk_manager_bgmn_doc_v1;

ALTER TABLE pm_project
    ADD COLUMN website_url VARCHAR(500) DEFAULT NULL COMMENT '项目网站地址' AFTER description,
    ADD COLUMN repository_url VARCHAR(500) DEFAULT NULL COMMENT 'GitLab或Git仓库地址' AFTER website_url;
