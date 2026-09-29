package com.carbackend.core;

/**
 * 自定义小车指令异常
 * 当接收到未知或非法的指令时抛出
 */
public class CarCommandException extends RuntimeException {

    /**
     * 构造方法：传入异常提示信息
     *
     * @param message 异常提示信息
     */
    public CarCommandException(String message) {
        super(message);
    }
}
