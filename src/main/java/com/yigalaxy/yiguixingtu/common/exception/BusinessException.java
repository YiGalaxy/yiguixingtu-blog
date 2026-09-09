package com.yigalaxy.yiguixingtu.common.exception;


import com.yigalaxy.yiguixingtu.common.ResultCode;
import lombok.Getter;

/**
 * 自定义业务异常：业务逻辑出错时 throw 它，由全局异常处理器统一转成 Result
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode){
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {                    // 给全局处理器拿错误码用
        return resultCode;
    }

}
