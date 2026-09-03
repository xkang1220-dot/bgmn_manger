package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.entity.SysFile;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface SysFileService {

    SysFile upload(MultipartFile file, String bizType, Long bizId);

    void bindBiz(List<Long> fileIds, String bizType, Long bizId);

    List<SysFile> listByBiz(String bizType, Long bizId);

    /** 按业务 ID 批量拉取附件，避免列表 N+1 */
    Map<Long, List<SysFile>> mapByBiz(String bizType, Collection<Long> bizIds);

    Page<SysFile> pageFiles(long page, long pageSize, String originalName, String bizType);

    void deleteFile(Long id);

    Resource load(Long id);

    SysFile get(Long id);
}
