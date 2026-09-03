package com.kk.system.totp;

import lombok.Data;

@Data
public class TotpVerifyRequest {

    private String totpCode;
}
