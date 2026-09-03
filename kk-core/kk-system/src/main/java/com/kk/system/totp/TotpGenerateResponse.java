package com.kk.system.totp;

import lombok.Data;

@Data
public class TotpGenerateResponse {

    private String accountName;

    private String secretKey;

    private String qrString;
}
