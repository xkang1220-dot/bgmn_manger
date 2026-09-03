package com.kk.biz.workflow;

/**
 * 审批类型常量
 */
public final class ApprovalTypes {

    private ApprovalTypes() {
    }

    public static final String PROJECT_CREATE = "PROJECT_CREATE";
    public static final String PROJECT_DELETE = "PROJECT_DELETE";
    public static final String REIMBURSE_PERSONAL = "REIMBURSE_PERSONAL";
    public static final String REIMBURSE_PROJECT = "REIMBURSE_PROJECT";
    public static final String PROJECT_ADVANCE = "PROJECT_ADVANCE";
    public static final String SHARE_CONFIG = "SHARE_CONFIG";
    public static final String PROJECT_SETTLE = "PROJECT_SETTLE";
    public static final String SALARY_APPLY = "SALARY_APPLY";
    public static final String RESERVE_RETURN = "RESERVE_RETURN";
    /** 公司总账登记：入账 / 出账 */
    public static final String LEDGER_REGISTER = "LEDGER_REGISTER";
    public static final String ROLLBACK = "ROLLBACK";
    /** 渠道月度核验（账户截图 + 流水凭证） */
    public static final String MONTHLY_VERIFY = "MONTHLY_VERIFY";

    public static String label(String type) {
        if (type == null) {
            return "审批";
        }
        return switch (type) {
            case PROJECT_CREATE -> "创建项目";
            case PROJECT_DELETE -> "删除项目";
            case REIMBURSE_PERSONAL -> "个人报销";
            case REIMBURSE_PROJECT -> "项目报销";
            case PROJECT_ADVANCE -> "项目预支";
            case SHARE_CONFIG -> "分成配置";
            case PROJECT_SETTLE -> "项目分钱";
            case SALARY_APPLY -> "工资申请";
            case RESERVE_RETURN -> "预留回公司";
            case LEDGER_REGISTER -> "总账登记";
            case ROLLBACK -> "资金回退";
            case MONTHLY_VERIFY -> "月度核验";
            default -> type;
        };
    }

    /** 是否需要财务回执 + 申请人确认到账后才动账 */
    public static boolean needMoneyConfirm(String type) {
        return REIMBURSE_PERSONAL.equals(type)
                || REIMBURSE_PROJECT.equals(type)
                || SALARY_APPLY.equals(type);
    }

    /** 是否全体股东会签 */
    public static boolean needAllShareholders(String type) {
        return PROJECT_CREATE.equals(type)
                || PROJECT_DELETE.equals(type)
                || SHARE_CONFIG.equals(type);
    }

    /** 是否真正动过账、允许发起资金回退（配置/建删项目等无金额审批不可回退） */
    public static boolean canMoneyRollback(String type) {
        return PROJECT_ADVANCE.equals(type)
                || PROJECT_SETTLE.equals(type)
                || REIMBURSE_PROJECT.equals(type)
                || REIMBURSE_PERSONAL.equals(type)
                || SALARY_APPLY.equals(type)
                || LEDGER_REGISTER.equals(type)
                || RESERVE_RETURN.equals(type);
    }
}
