package com.kk.biz.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.entity.SysFile;
import com.kk.biz.mapper.SysFileMapper;
import com.kk.biz.service.SysFileService;
import com.kk.common.exception.BusinessException;
import com.kk.oss.FileStorage;
import com.kk.oss.FileStorageProperties;
import com.kk.oss.LocalFileStorage;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysFileServiceImpl implements SysFileService {

    private final SysFileMapper fileMapper;
    private final SysUserService userService;
    private final FileStorage fileStorage;
    private final FileStorageProperties storageProperties;

    @Override
    public SysFile upload(MultipartFile file, String bizType, Long bizId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择文件");
        }
        long maxBytes = (long) storageProperties.getMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException("文件大小不能超过 " + storageProperties.getMaxSizeMb() + "MB");
        }
        String original = file.getOriginalFilename();
        String ext = FileUtil.extName(original);
        String stored = IdUtil.fastSimpleUUID() + (StringUtils.hasText(ext) ? "." + ext : "");
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String storagePath = storageProperties.buildStoragePath(datePath, bizType);
        String objectPath = storagePath + "/" + stored;
        try (InputStream inputStream = file.getInputStream()) {
            String url = fileStorage.upload(
                    inputStream,
                    storagePath,
                    stored,
                    file.getContentType(),
                    file.getSize()
            );
            SysFile record = new SysFile();
            record.setOriginalName(original);
            record.setStoredName(stored);
            record.setPath(objectPath);
            record.setUrl(url);
            record.setStorageType(fileStorage.getStorageType());
            record.setContentType(file.getContentType());
            record.setSize(file.getSize());
            record.setBizType(bizType);
            record.setBizId(bizId);
            fileMapper.insert(record);
            if (url == null) {
                record.setUrl("/api/file/download/" + record.getId());
                fileMapper.updateById(record);
            }
            return record;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public void bindBiz(List<Long> fileIds, String bizType, Long bizId) {
        if (fileIds == null || fileIds.isEmpty() || bizId == null) {
            return;
        }
        for (Long fileId : fileIds) {
            SysFile file = fileMapper.selectById(fileId);
            if (file == null) {
                continue;
            }
            SysFile update = new SysFile();
            update.setId(fileId);
            update.setBizType(bizType);
            update.setBizId(bizId);
            fileMapper.updateById(update);
        }
    }

    @Override
    public List<SysFile> listByBiz(String bizType, Long bizId) {
        if (!StringUtils.hasText(bizType) || bizId == null) {
            return List.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getBizType, bizType)
                .eq(SysFile::getBizId, bizId)
                .orderByAsc(SysFile::getId));
    }

    @Override
    public Map<Long, List<SysFile>> mapByBiz(String bizType, Collection<Long> bizIds) {
        if (!StringUtils.hasText(bizType) || bizIds == null || bizIds.isEmpty()) {
            return Map.of();
        }
        List<SysFile> files = fileMapper.selectList(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getBizType, bizType)
                .in(SysFile::getBizId, bizIds)
                .orderByAsc(SysFile::getId));
        Map<Long, List<SysFile>> map = new HashMap<>();
        for (SysFile file : files) {
            ensureDownloadUrl(file);
            map.computeIfAbsent(file.getBizId(), k -> new ArrayList<>()).add(file);
        }
        return map;
    }

    @Override
    public Page<SysFile> pageFiles(long page, long pageSize, String originalName, String bizType) {
        Page<SysFile> result = fileMapper.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<SysFile>()
                .like(StringUtils.hasText(originalName), SysFile::getOriginalName, originalName)
                .eq(StringUtils.hasText(bizType), SysFile::getBizType, bizType)
                .orderByDesc(SysFile::getId));
        fillExtras(result.getRecords());
        return result;
    }

    @Override
    public void deleteFile(Long id) {
        SysFile file = get(id);
        fileStorage.delete(file.getPath());
        fileMapper.deleteById(id);
    }

    @Override
    public Resource load(Long id) {
        SysFile file = get(id);
        if (!fileStorage.exists(file.getPath())) {
            throw new BusinessException("文件不存在");
        }
        return new InputStreamResource(fileStorage.open(file.getPath())) {
            @Override
            public String getFilename() {
                return file.getOriginalName();
            }
        };
    }

    @Override
    public SysFile get(Long id) {
        SysFile file = fileMapper.selectById(id);
        if (file == null) {
            throw new BusinessException("文件记录不存在");
        }
        fillExtra(file);
        return file;
    }

    private void fillExtra(SysFile file) {
        fillExtras(List.of(file));
    }

    private void fillExtras(List<SysFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Set<Long> userIds = new HashSet<>();
        for (SysFile file : files) {
            ensureDownloadUrl(file);
            if (file.getCreateBy() != null) {
                userIds.add(file.getCreateBy());
            }
        }
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u -> userMap.put(u.getId(), u));
        }
        for (SysFile file : files) {
            if (file.getCreateBy() == null) {
                continue;
            }
            SysUser user = userMap.get(file.getCreateBy());
            if (user != null) {
                file.setUploaderName(user.getNickname() != null ? user.getNickname() : user.getUsername());
            }
        }
    }

    private void ensureDownloadUrl(SysFile file) {
        if (file.getUrl() == null && LocalFileStorage.STORAGE_TYPE.equals(file.getStorageType())) {
            file.setUrl("/api/file/download/" + file.getId());
        }
    }
}
