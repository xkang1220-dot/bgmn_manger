package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrPayMethod;

import java.util.List;

public interface HrArchiveService extends IService<HrArchive> {

    Page<HrArchive> pageArchives(long page, long pageSize, String realName, String employeeNo);

    HrArchive getDetail(Long id);

    void createArchive(HrArchive archive);

    void updateArchive(HrArchive archive);

    void deleteArchive(Long id);

    HrArchive getByUserId(Long userId);

    /** 当前登录人档案（无则返回预填空壳，不落库） */
    HrArchive getMine(Long userId);

    /** 当前登录人保存自己的档案（无则创建，有则更新） */
    void saveMine(Long userId, HrArchive archive);

    /** 当前用户档案下的收款方式 */
    List<HrPayMethod> listMyPayMethods(Long userId);

    /** 校验收款方式归属当前用户档案 */
    HrPayMethod getOwnedMethod(Long userId, Long methodId);
}
