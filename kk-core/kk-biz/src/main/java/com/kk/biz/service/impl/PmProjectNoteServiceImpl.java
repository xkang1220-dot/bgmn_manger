package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.entity.PmProjectNote;
import com.kk.biz.entity.SysFile;
import com.kk.biz.mapper.PmProjectNoteMapper;
import com.kk.biz.service.PmProjectNoteService;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.SysFileService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmProjectNoteServiceImpl implements PmProjectNoteService {

    private static final String PROJECT_NOTE_FILE_BIZ = "project_note";
    private static final int MAX_PLAIN_TEXT = 5000;
    private static final Safelist NOTE_HTML_SAFELIST = Safelist.basic()
            .addTags("p", "div", "br", "ul", "ol", "li")
            .addAttributes("a", "target", "rel")
            .addProtocols("a", "href", "http", "https", "mailto");

    private final PmProjectNoteMapper noteMapper;
    private final PmProjectService projectService;
    private final SysFileService fileService;
    private final SysUserService userService;
    private final DataScopeService dataScopeService;

    @Override
    public List<PmProjectNote> listNotes(Long projectId) {
        projectService.assertCanView(projectId, StpUtil.getLoginIdAsLong());
        List<PmProjectNote> list = noteMapper.selectList(new LambdaQueryWrapper<PmProjectNote>()
                .eq(PmProjectNote::getProjectId, projectId)
                .orderByDesc(PmProjectNote::getId));
        list.forEach(note -> note.setContent(Jsoup.clean(
                note.getContent() == null ? "" : note.getContent(), NOTE_HTML_SAFELIST)));
        fillAuthors(list);
        fillAttachments(list);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmProjectNote addNote(Long projectId, String content, List<Long> fileIds) {
        projectService.assertCanView(projectId, StpUtil.getLoginIdAsLong());
        String safeHtml = Jsoup.clean(content == null ? "" : content.trim(), NOTE_HTML_SAFELIST);
        String plainText = Jsoup.parse(safeHtml).text().trim();
        boolean hasAttachments = fileIds != null && !fileIds.isEmpty();
        if (!StringUtils.hasText(plainText) && !hasAttachments) {
            throw new BusinessException("备注内容不能为空");
        }
        if (plainText.length() > MAX_PLAIN_TEXT) {
            throw new BusinessException("备注不能超过 " + MAX_PLAIN_TEXT + " 字");
        }
        PmProjectNote note = new PmProjectNote();
        note.setProjectId(projectId);
        note.setContent(safeHtml);
        noteMapper.insert(note);
        bindAttachments(fileIds, note.getId());
        fillAuthors(List.of(note));
        fillAttachments(List.of(note));
        return note;
    }

    @Override
    public SysFile uploadAttachment(MultipartFile file) {
        return fileService.upload(file, PROJECT_NOTE_FILE_BIZ, null);
    }

    @Override
    public void deleteAttachment(Long fileId) {
        SysFile file = fileService.get(fileId);
        if (!PROJECT_NOTE_FILE_BIZ.equals(file.getBizType()) || file.getBizId() != null) {
            throw new BusinessException("无权删除该备注附件");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (file.getCreateBy() != null && !file.getCreateBy().equals(loginId)
                && !dataScopeService.isGlobalAdmin(loginId)) {
            throw new BusinessException("只能删除自己上传的附件");
        }
        fileService.deleteFile(fileId);
    }

    private void bindAttachments(List<Long> fileIds, Long noteId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        List<Long> allowed = new ArrayList<>();
        for (Long fileId : fileIds.stream().distinct().toList()) {
            SysFile file = fileService.get(fileId);
            if (!PROJECT_NOTE_FILE_BIZ.equals(file.getBizType()) || file.getBizId() != null
                    || (file.getCreateBy() != null && !file.getCreateBy().equals(loginId))) {
                throw new BusinessException("备注附件无效或无权使用");
            }
            allowed.add(fileId);
        }
        fileService.bindBiz(allowed, PROJECT_NOTE_FILE_BIZ, noteId);
    }

    private void fillAttachments(List<PmProjectNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        Map<Long, List<SysFile>> files = fileService.mapByBiz(PROJECT_NOTE_FILE_BIZ,
                notes.stream().map(PmProjectNote::getId).toList());
        notes.forEach(note -> note.setAttachments(files.getOrDefault(note.getId(), List.of())));
    }

    private void fillAuthors(List<PmProjectNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        Set<Long> userIds = notes.stream()
                .map(PmProjectNote::getCreateBy)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, SysUser> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (PmProjectNote note : notes) {
            SysUser user = userMap.get(note.getCreateBy());
            if (user != null) {
                note.setAuthorName(user.getNickname() != null ? user.getNickname() : user.getUsername());
            }
        }
    }
}
