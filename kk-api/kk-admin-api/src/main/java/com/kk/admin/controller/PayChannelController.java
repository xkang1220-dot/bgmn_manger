package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.entity.FinMonthVerify;
import com.kk.biz.entity.FinPayChannel;
import com.kk.biz.mapper.FinMonthVerifyMapper;
import com.kk.biz.service.FinPayChannelService;
import com.kk.biz.service.SysFileService;
import com.kk.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.system.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class PayChannelController {

    private final FinPayChannelService payChannelService;
    private final FinMonthVerifyMapper monthVerifyMapper;
    private final SysFileService fileService;
    private final DataScopeService dataScopeService;

    @GetMapping("/pay-channel/list")
    @SaCheckPermission(value = {"finance:channel:list", "finance:ledger:list", "finance:ledger:add"}, mode = SaMode.OR)
    public Result<List<FinPayChannel>> list(@RequestParam(required = false) Long poolId,
                                            @RequestParam(required = false, defaultValue = "false") boolean all) {
        return Result.ok(all ? payChannelService.listAll(poolId) : payChannelService.listEnabled(poolId));
    }

    @PostMapping("/pay-channel")
    @SaCheckPermission("finance:channel:edit")
    public Result<Void> create(@RequestBody FinPayChannel channel) {
        payChannelService.createChannel(channel);
        return Result.ok();
    }

    @PutMapping("/pay-channel")
    @SaCheckPermission("finance:channel:edit")
    public Result<Void> update(@RequestBody FinPayChannel channel) {
        payChannelService.updateChannel(channel);
        return Result.ok();
    }

    @GetMapping("/month-verify/list")
    @SaCheckPermission("finance:verify:list")
    public Result<List<FinMonthVerify>> verifyList(@RequestParam(required = false) String verifyMonth,
                                                   @RequestParam(required = false) Long channelId) {
        LambdaQueryWrapper<FinMonthVerify> wrapper = new LambdaQueryWrapper<FinMonthVerify>()
                .eq(StringUtils.hasText(verifyMonth), FinMonthVerify::getVerifyMonth, verifyMonth)
                .eq(channelId != null, FinMonthVerify::getChannelId, channelId)
                .orderByDesc(FinMonthVerify::getVerifyMonth)
                .orderByDesc(FinMonthVerify::getId);
        long loginId = StpUtil.getLoginIdAsLong();
        if (!dataScopeService.isGlobalAdmin(loginId)) {
            Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
            if (companies.isEmpty()) {
                return Result.ok(List.of());
            }
            wrapper.in(FinMonthVerify::getCompanyId, companies);
        }
        List<FinMonthVerify> list = monthVerifyMapper.selectList(wrapper);
        fillVerify(list);
        return Result.ok(list);
    }

    private void fillVerify(List<FinMonthVerify> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> channelIds = list.stream().map(FinMonthVerify::getChannelId).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, FinPayChannel> channelMap = new HashMap<>();
        if (!channelIds.isEmpty()) {
            payChannelService.listByIds(channelIds).forEach(c -> channelMap.put(c.getId(), c));
        }
        Set<Long> ids = list.stream().map(FinMonthVerify::getId).collect(Collectors.toSet());
        var fileMap = fileService.mapByBiz("month_verify", ids);
        for (FinMonthVerify v : list) {
            FinPayChannel c = channelMap.get(v.getChannelId());
            if (c != null) {
                v.setChannelName(c.getName());
                v.setChannelType(c.getChannelType());
            }
            v.setVouchers(fileMap.getOrDefault(v.getId(), List.of()));
        }
    }
}
