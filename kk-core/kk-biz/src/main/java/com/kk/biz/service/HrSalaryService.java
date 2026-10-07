package com.kk.biz.service;

import com.kk.biz.entity.HrSalaryItem;
import com.kk.biz.entity.HrSalaryRun;
import com.kk.biz.entity.HrSalaryRunLine;
import com.kk.biz.entity.HrSalarySchedule;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

public interface HrSalaryService {

    List<HrSalaryItem> listItems(Long companyId, Long userId);

    List<HrSalaryItem> listBudgetItems();

    void saveItem(HrSalaryItem item);

    void deleteItem(Long id);

    HrSalarySchedule getSchedule(Long companyId);

    void saveSchedule(HrSalarySchedule schedule);

    List<HrSalaryRun> listRuns(Long companyId, String yearMonth);

    List<HrSalaryRunLine> listRunLines(Long runId);

    /** 作废尚未进入发薪流程的预算明细。 */
    void voidRunLine(Long lineId, String reason);

    /** 按工资配置即时测算，不产生任何数据库记录。 */
    Map<String, Object> previewBudget(String yearMonth, List<Long> itemIds, Map<Long, BigDecimal> taskRewardOverrides);

    /** 将重新计算后的预算结果保存为预算批次。 */
    List<HrSalaryRun> saveBudget(String yearMonth, List<Long> itemIds, Map<Long, BigDecimal> taskRewardOverrides);

    /** 当前登录人：待确认/已确认的本月预告 */
    List<Map<String, Object>> myConfirmQueue(String yearMonth);

    void confirmMine(Long lineId);

    void revokeMine(Long lineId);

    HrSalaryRun runPreview(Long companyId, String yearMonth);

    HrSalaryRun runPay(Long companyId, String yearMonth);

    /** 定时扫描所有公司 */
    void scanSchedules();
}
