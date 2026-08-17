package com.fanshop.support.error;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ErrorMessage {

    private String code;

    private String message;

    private Object data;

    public ErrorMessage(ErrorType errorType) {
        this.code = errorType.getCode().name();
        this.message = errorType.getMessage();
        this.data = null;
    }

    public ErrorMessage(ErrorType errorType, Object data) {
        this.code = errorType.getCode().name();
        this.message = errorType.getMessage();
        this.data = data;
    }

}
