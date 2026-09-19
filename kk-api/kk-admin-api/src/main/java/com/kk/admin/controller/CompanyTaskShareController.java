package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.dto.CompanyTaskShareOption;
import com.kk.biz.dto.CompanyTaskShareState;
import com.kk.biz.service.CompanyTaskShareService;
import com.kk.common.result.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/task/company-share")
@SaCheckPermission("project:task:share")
@RequiredArgsConstructor
public class CompanyTaskShareController {

    private final CompanyTaskShareService companyTaskShareService;

    @GetMapping("/options")
    public Result<List<CompanyTaskShareOption>> options() {
        return Result.ok(companyTaskShareService.listOptions(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/members")
    public Result<List<CompanyTaskShareOption>> members(@RequestParam Long companyId) {
        return Result.ok(companyTaskShareService.listMembers(StpUtil.getLoginIdAsLong(), companyId));
    }

    @GetMapping
    public Result<CompanyTaskShareState> state(@RequestParam Long companyId) {
        return Result.ok(companyTaskShareService.state(StpUtil.getLoginIdAsLong(), companyId));
    }

    @PostMapping
    public Result<CompanyTaskShareState> generate(@RequestBody(required = false) ShareBody body) {
        Long companyId = body == null ? null : body.getCompanyId();
        List<Long> userIds = body == null ? null : body.getUserIds();
        String from = body == null ? null : body.getFrom();
        String to = body == null ? null : body.getTo();
        return Result.ok(companyTaskShareService.generate(StpUtil.getLoginIdAsLong(), companyId, userIds, from, to));
    }

    @PutMapping
    public Result<CompanyTaskShareState> updateUsers(@RequestBody(required = false) ShareBody body) {
        Long companyId = body == null ? null : body.getCompanyId();
        List<Long> userIds = body == null ? null : body.getUserIds();
        String from = body == null ? null : body.getFrom();
        String to = body == null ? null : body.getTo();
        return Result.ok(companyTaskShareService.updateUsers(StpUtil.getLoginIdAsLong(), companyId, userIds, from, to));
    }

    @DeleteMapping
    public Result<Void> revoke(@RequestParam Long companyId) {
        companyTaskShareService.revoke(StpUtil.getLoginIdAsLong(), companyId);
        return Result.ok();
    }

    @Data
    public static class ShareBody {
        private Long companyId;
        private List<Long> userIds;
        private String from;
        private String to;
    }
}
