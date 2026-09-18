package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.entity.FaAsset;
import com.kk.biz.entity.SysFile;
import com.kk.biz.enums.FaItemType;
import com.kk.biz.service.FaAssetService;
import com.kk.biz.service.SysFileService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/fa/asset")
@RequiredArgsConstructor
public class FaAssetController {

    private final FaAssetService assetService;
    private final SysFileService fileService;

    @GetMapping("/page")
    @SaCheckPermission("fa:asset:list")
    public Result<PageResult<FaAsset>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "12") long pageSize,
                                            @RequestParam(required = false) Long companyId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long holderUserId,
                                            @RequestParam(required = false) String itemType,
                                            @RequestParam(required = false) String keyword) {
        String type = StringUtils.hasText(itemType) ? itemType.trim() : null;
        return Result.ok(PageResult.of(assetService.pageAssets(
                page, pageSize, companyId, status, holderUserId, type, keyword)));
    }

    @GetMapping("/item-types")
    @SaCheckPermission("fa:asset:list")
    public Result<Map<String, String>> itemTypes() {
        return Result.ok(FaItemType.options());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("fa:asset:list")
    public Result<FaAsset> detail(@PathVariable Long id) {
        return Result.ok(assetService.detail(id));
    }

    @PostMapping("/image")
    @SaCheckPermission(value = {"fa:asset:add", "fa:asset:edit"}, mode = SaMode.OR)
    public Result<SysFile> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.ok(fileService.upload(file, "fa_asset_image", null));
    }

    @PostMapping
    @SaCheckPermission("fa:asset:add")
    public Result<Void> create(@RequestBody FaAsset asset) {
        assetService.createAsset(asset);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("fa:asset:edit")
    public Result<Void> update(@RequestBody FaAsset asset) {
        assetService.updateAsset(asset);
        return Result.ok();
    }
}
