package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrDutyRecord;
import com.kk.biz.mapper.HrArchiveMapper;
import com.kk.biz.mapper.HrDutyRecordMapper;
import com.kk.biz.service.HrDutyService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HrDutyServiceImpl extends ServiceImpl<HrDutyRecordMapper, HrDutyRecord>
        implements HrDutyService {

    private final HrArchiveMapper archiveMapper;
    private final SysUserService userService;

    @Override
    public List<HrDutyRecord> listDuty(LocalDate start, LocalDate end) {
        List<HrDutyRecord> rows = list(new LambdaQueryWrapper<HrDutyRecord>()
                .ge(start != null, HrDutyRecord::getDutyDate, start)
                .le(end != null, HrDutyRecord::getDutyDate, end)
                .orderByAsc(HrDutyRecord::getDutyDate)
                .orderByAsc(HrDutyRecord::getUserId));
        Set<Long> userIds = rows.stream().map(HrDutyRecord::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(user -> names.put(user.getId(),
                    StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername()));
        }
        rows.forEach(row -> row.setUserName(names.get(row.getUserId())));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDutyUsers(LocalDate dutyDate, List<Long> userIds) {
        if (dutyDate == null) {
            throw new BusinessException("请选择日期");
        }
        Set<Long> attendanceUserIds = archiveMapper.selectList(new LambdaQueryWrapper<HrArchive>()
                        .select(HrArchive::getUserId)
                        .eq(HrArchive::getAttendanceEnabled, 1))
                .stream().map(HrArchive::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> enabledUsers = attendanceUserIds.isEmpty() ? Set.of() : userService.list(
                        new LambdaQueryWrapper<SysUser>().select(SysUser::getId)
                                .in(SysUser::getId, attendanceUserIds).eq(SysUser::getStatus, 1))
                .stream().map(SysUser::getId).collect(Collectors.toSet());
        Set<Long> selected = userIds == null ? Set.of() : userIds.stream()
                .filter(Objects::nonNull)
                .filter(enabledUsers::contains)
                .collect(Collectors.toSet());
        baseMapper.deleteDutyDay(dutyDate);
        for (Long userId : selected) {
            HrDutyRecord row = new HrDutyRecord();
            row.setUserId(userId);
            row.setDutyDate(dutyDate);
            save(row);
        }
    }
}
