package com.carbackend.core;

/**
 * 后退指令
 */
public class BackwardCommand implements CarCommand {

    /**
     * 执行后退操作
     */
    @Override
    public void execute() {
        System.out.println("🚗 小车正在后退...");
    }
}
