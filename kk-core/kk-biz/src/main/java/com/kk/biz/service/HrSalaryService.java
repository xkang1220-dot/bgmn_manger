package com.kk.biz.service;

import com.kk.biz.entity.HrSalaryItem;
import com.kk.biz.entity.HrSalaryRun;
import com.kk.biz.entity.HrSalaryRunLine;
import com.kk.biz.entity.HrSalarySchedule;

import java.util.List;
import java.util.Map;

public interface HrSalaryService {

    List<HrSalaryItem> listItems(Long companyId, Long userId);

    void saveItem(HrSalaryItem item);

    void deleteItem(Long id);

    HrSalarySchedule getSchedule(Long companyId);

    void saveSchedule(HrSalarySchedule schedule);

    List<HrSalaryRun> listRuns(Long companyId, String yearMonth);

    List<HrSalaryRunLine> listRunLines(Long runId);

    /** 当前登录人：待确认/已确认的本月预告 */
    List<Map<String, Object>> myConfirmQueue(String yearMonth);

    void confirmMine(Long lineId);

    void revokeMine(Long lineId);

    /** 发薪准备草稿：含考勤请假天数 */
    List<Map<String, Object>> preparePayDraft(Long companyId, String yearMonth);

    /**
     * 财务填写扣款后发送确认（替代定时预告；员工确认后再手动发薪生成审批）
     */
    HrSalaryRun preparePayConfirm(Long companyId, String yearMonth, List<Map<String, Object>> lines);

    HrSalaryRun runPreview(Long companyId, String yearMonth, boolean manual);

    HrSalaryRun runPay(Long companyId, String yearMonth, boolean manual);

    /** 定时扫描所有公司 */
    void scanSchedules();
}
