package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.entity.FaAsset;
import com.kk.biz.service.FaAssetService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fa/asset")
@RequiredArgsConstructor
public class FaAssetController {

    private final FaAssetService assetService;

    @GetMapping("/page")
    @SaCheckPermission("fa:asset:list")
    public Result<PageResult<FaAsset>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "12") long pageSize,
                                            @RequestParam(required = false) Long companyId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long holderUserId,
                                            @RequestParam(required = false) String keyword) {
        return Result.ok(PageResult.of(assetService.pageAssets(page, pageSize, companyId, status, holderUserId, keyword)));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("fa:asset:list")
    public Result<FaAsset> detail(@PathVariable Long id) {
        return Result.ok(assetService.detail(id));
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
