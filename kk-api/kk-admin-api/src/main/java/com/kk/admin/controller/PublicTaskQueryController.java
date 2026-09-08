package com.kk.admin.controller;

import com.kk.biz.dto.PublicTaskQueryRequest;
import com.kk.biz.dto.PublicTaskQueryResult;
import com.kk.biz.service.PublicTaskQueryService;
import com.kk.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicTaskQueryController {

    private final PublicTaskQueryService publicTaskQueryService;

    @PostMapping("/task-query")
    public Result<PublicTaskQueryResult> query(@RequestBody(required = false) PublicTaskQueryRequest body,
                                               HttpServletRequest request) {
        String code = body == null ? null : body.getCode();
        return Result.ok(publicTaskQueryService.query(code, clientIp(request)));
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
