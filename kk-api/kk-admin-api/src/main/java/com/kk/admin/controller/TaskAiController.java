package com.kk.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.dto.TaskAiModels.*;
import com.kk.biz.service.TaskAiService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/task-ai")
@RequiredArgsConstructor
public class TaskAiController {
    private final TaskAiService taskAiService;

    @PostMapping("/chat") public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        return Result.ok(taskAiService.chat(StpUtil.getLoginIdAsLong(), request));
    }
    @PostMapping("/confirm") public Result<ConfirmResponse> confirm(@RequestBody ConfirmRequest request) {
        return Result.ok(taskAiService.confirm(StpUtil.getLoginIdAsLong(), request));
    }
    @PostMapping("/cancel") public Result<Void> cancel(@RequestBody CancelRequest request) {
        taskAiService.cancel(StpUtil.getLoginIdAsLong(), request.getSessionId()); return Result.ok();
    }
    @GetMapping("/tools") public Result<List<Map<String,String>>> tools() { return Result.ok(taskAiService.tools()); }
}
