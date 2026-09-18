package com.kk.biz.workflow;



/**

 * 审批类型常量

 */

public final class ApprovalTypes {



    private ApprovalTypes() {

    }



    public static final String PROJECT_CREATE = "PROJECT_CREATE";

    public static final String PROJECT_DELETE = "PROJECT_DELETE";

    /** 变更项目规模（改到重点/重大，含重点↔重大） */

    public static final String PROJECT_SCALE_CHANGE = "PROJECT_SCALE_CHANGE";

    public static final String REIMBURSE_PERSONAL = "REIMBURSE_PERSONAL";

    /** 个人钱包提现（含税：扣全额，税进公司资金池） */

    public static final String WALLET_WITHDRAW = "WALLET_WITHDRAW";

    public static final String REIMBURSE_PROJECT = "REIMBURSE_PROJECT";

    /** 从项目结余申请余额到个人钱包（无需发票；审批通过即入账） */

    public static final String PROJECT_BALANCE_APPLY = "PROJECT_BALANCE_APPLY";

    public static final String PROJECT_ADVANCE = "PROJECT_ADVANCE";
    /** 把公司转入项目的非分成资金退回公司总账 */
    public static final String PROJECT_ADVANCE_RETURN = "PROJECT_ADVANCE_RETURN";

    public static final String SHARE_CONFIG = "SHARE_CONFIG";

    public static final String PROJECT_SETTLE = "PROJECT_SETTLE";

    /** 自然月对待分成余额按配置分层（分成入钱包 + 预留占用） */

    public static final String PROJECT_SHARE_PERIOD = "PROJECT_SHARE_PERIOD";

    public static final String SALARY_APPLY = "SALARY_APPLY";

    /** 月度工资（配置汇总，一人一单，通过即入账） */

    public static final String SALARY_MONTHLY = "SALARY_MONTHLY";

    public static final String RESERVE_RETURN = "RESERVE_RETURN";

    /** 公司总账登记：入账 / 出账 */

    public static final String LEDGER_REGISTER = "LEDGER_REGISTER";

    public static final String ROLLBACK = "ROLLBACK";

    /** 渠道月度核验（账户截图 + 流水凭证） */

    public static final String MONTHLY_VERIFY = "MONTHLY_VERIFY";

    /** 固定资产领用 */

    public static final String ASSET_BORROW = "ASSET_BORROW";

    /** 固定资产归还 */

    public static final String ASSET_RETURN = "ASSET_RETURN";

    /** 固定资产转交（领用人流转） */

    public static final String ASSET_TRANSFER = "ASSET_TRANSFER";



    public static String label(String type) {

        if (type == null) {

            return "审批";

        }

        return switch (type) {

            case PROJECT_CREATE -> "创建项目";

            case PROJECT_DELETE -> "删除项目";

            case PROJECT_SCALE_CHANGE -> "变更项目规模";

            case REIMBURSE_PERSONAL -> "个人报销";

            case WALLET_WITHDRAW -> "钱包提现";

            case REIMBURSE_PROJECT -> "项目报销";

            case PROJECT_BALANCE_APPLY -> "项目余额申请";

            case PROJECT_ADVANCE -> "项目预支";
            case PROJECT_ADVANCE_RETURN -> "退回公司";

            case SHARE_CONFIG -> "分成配置";

            case PROJECT_SETTLE -> "项目分钱";

            case PROJECT_SHARE_PERIOD -> "自然月分层";

            case SALARY_APPLY -> "工资申请";

            case SALARY_MONTHLY -> "月度工资";

            case RESERVE_RETURN -> "预留回公司";

            case LEDGER_REGISTER -> "总账登记";

            case ROLLBACK -> "资金回退";

            case MONTHLY_VERIFY -> "月度核验";

            case ASSET_BORROW -> "资产领用";

            case ASSET_RETURN -> "资产归还";

            case ASSET_TRANSFER -> "资产转交";

            default -> type;

        };

    }



    /** 是否需要财务回执 + 申请人确认到账后才动账 */

    public static boolean needMoneyConfirm(String type) {

        return REIMBURSE_PERSONAL.equals(type)

                || REIMBURSE_PROJECT.equals(type)

                || SALARY_APPLY.equals(type)

                || WALLET_WITHDRAW.equals(type);

    }



    /** @deprecated 审批人与会签/或签已由 wf_approval_flow 按公司配置，勿再按类型写死 */

    @Deprecated

    public static boolean needAllShareholders(String type) {

        return false;

    }



    /** 是否真正动过账、允许发起资金回退（配置/建删项目等无金额审批不可回退） */

    public static boolean canMoneyRollback(String type) {

        return PROJECT_ADVANCE.equals(type)
                || PROJECT_ADVANCE_RETURN.equals(type)

                || PROJECT_SETTLE.equals(type)

                || REIMBURSE_PROJECT.equals(type)

                || PROJECT_BALANCE_APPLY.equals(type)

                || REIMBURSE_PERSONAL.equals(type)

                || WALLET_WITHDRAW.equals(type)

                || SALARY_APPLY.equals(type)

                || SALARY_MONTHLY.equals(type)

                || RESERVE_RETURN.equals(type)

                || LEDGER_REGISTER.equals(type);

    }

}


