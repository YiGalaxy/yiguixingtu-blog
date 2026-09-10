package com.yigalaxy.yiguixingtu.common.exception;

import com.yigalaxy.yiguixingtu.common.ResultCode;

/**
 * 自定义业务异常：业务逻辑出错时 throw 它，由全局异常处理器统一转成 Result
 */
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
