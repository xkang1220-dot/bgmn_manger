package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.HrArchive;

public interface HrArchiveService extends IService<HrArchive> {

    Page<HrArchive> pageArchives(long page, long pageSize, String realName, String employeeNo);

    HrArchive getDetail(Long id);

    void createArchive(HrArchive archive);

    void updateArchive(HrArchive archive);

    void deleteArchive(Long id);

    HrArchive getByUserId(Long userId);
}
