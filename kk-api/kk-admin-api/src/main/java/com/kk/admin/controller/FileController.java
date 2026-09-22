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
        // 始终经后端读存储再输出，避免对象存储外网域名不可达（502）导致无法下载
        Resource resource = fileService.load(id);
        String filename = URLEncoder.encode(meta.getOriginalName(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(resolveMediaType(meta.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename);
        if (meta.getSize() != null && meta.getSize() >= 0) {
            builder.contentLength(meta.getSize());
        }
        return builder.body(resource);
    }

    @GetMapping("/preview/{id}")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        SysFile meta = fileService.get(id);
        // 预览必须走后端代理：公网直连 MinIO/RustFS 域名常 502，img 无法显示
        Resource resource = fileService.load(id);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(resolveMediaType(meta.getContentType()));
        if (meta.getSize() != null && meta.getSize() >= 0) {
            builder.contentLength(meta.getSize());
        }
        return builder.body(resource);
    }

    private static MediaType resolveMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
