package com.carbackend.core;

/**
 * 右转指令
 */
public class RightCommand implements CarCommand {

    /**
     * 执行右转操作
     */
    @Override
    public void execute() {
        System.out.println("小车右转");
    }
}
