package com.carbackend.core;

/**
 * 小车指令接口
 * 所有具体的指令（前进、停止、左转、右转）都需要实现该接口
 */
public interface CarCommand {

    /**
     * 执行指令
     */
    void execute();
}
