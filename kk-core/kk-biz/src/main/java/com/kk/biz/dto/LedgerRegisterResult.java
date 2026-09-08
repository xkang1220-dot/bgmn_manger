package com.kk.biz.dto;

import com.kk.biz.entity.WfApproval;
import lombok.Data;

@Data
public class LedgerRegisterResult {
    /** DIRECT=已直接记账 APPROVAL=已提交审批 */
    private String mode;
    private String message;
    private WfApproval approval;

    public static LedgerRegisterResult direct(String message) {
        LedgerRegisterResult r = new LedgerRegisterResult();
        r.setMode("DIRECT");
        r.setMessage(message);
        return r;
    }

    public static LedgerRegisterResult approval(WfApproval approval, String message) {
        LedgerRegisterResult r = new LedgerRegisterResult();
        r.setMode("APPROVAL");
        r.setMessage(message);
        r.setApproval(approval);
        return r;
    }
}
