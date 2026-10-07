package com.kk.biz.service;

import com.kk.biz.entity.PmProjectNote;
import com.kk.biz.entity.SysFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PmProjectNoteService {

    List<PmProjectNote> listNotes(Long projectId);

    PmProjectNote addNote(Long projectId, String content, List<Long> fileIds);

    SysFile uploadAttachment(MultipartFile file);

    void deleteAttachment(Long fileId);
}
