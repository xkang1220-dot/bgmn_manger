package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.entity.SysFile;
import com.kk.biz.service.SysFileService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final SysFileService fileService;

    @GetMapping("/page")
    @SaCheckPermission("file:list")
    public Result<PageResult<SysFile>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String originalName, String bizType) {
        return Result.ok(PageResult.of(fileService.pageFiles(page, pageSize, originalName, bizType)));
    }

    @PostMapping("/upload")
    @SaCheckPermission("file:upload")
    public Result<SysFile> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId) {
        return Result.ok(fileService.upload(file, bizType, bizId));
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("file:remove")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.deleteFile(id);
        return Result.ok();
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        SysFile meta = fileService.get(id);
        if (StringUtils.hasText(meta.getUrl()) && !meta.getUrl().startsWith("/api/")) {
            return ResponseEntity.status(302).location(java.net.URI.create(meta.getUrl())).build();
        }
        Resource resource = fileService.load(id);
        String filename = URLEncoder.encode(meta.getOriginalName(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        MediaType mediaType = StringUtils.hasText(meta.getContentType())
                ? MediaType.parseMediaType(meta.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(resource);
    }

    @GetMapping("/preview/{id}")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        SysFile meta = fileService.get(id);
        if (StringUtils.hasText(meta.getUrl()) && !meta.getUrl().startsWith("/api/")) {
            return ResponseEntity.status(302).location(java.net.URI.create(meta.getUrl())).build();
        }
        Resource resource = fileService.load(id);
        MediaType mediaType = StringUtils.hasText(meta.getContentType())
                ? MediaType.parseMediaType(meta.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(mediaType).body(resource);
    }
}
