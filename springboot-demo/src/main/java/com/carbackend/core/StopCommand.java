package com.carbackend.core;

/**
 * 停止指令
 */
public class StopCommand implements CarCommand {

    /**
     * 执行停止操作
     */
    @Override
    public void execute() {
        System.out.println("小车停止");
    }
}
