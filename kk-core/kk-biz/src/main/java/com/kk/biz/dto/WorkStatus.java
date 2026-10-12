package com.kk.biz.dto;

import java.time.LocalDateTime;

public record WorkStatus(boolean working, LocalDateTime changedAt) {
}
