package com.kk.admin.controller;

import com.kk.biz.dto.PublicCompanyTaskBoard;
import com.kk.biz.service.CompanyTaskShareService;
import com.kk.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicCompanyTaskController {

    private final CompanyTaskShareService companyTaskShareService;

    @GetMapping("/company-tasks/{token}")
    public Result<PublicCompanyTaskBoard> board(@PathVariable String token, HttpServletRequest request) {
        return Result.ok(companyTaskShareService.query(token, clientIp(request)));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
