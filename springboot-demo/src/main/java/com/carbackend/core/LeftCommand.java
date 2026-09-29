package com.carbackend.core;

/**
 * 左转指令
 */
public class LeftCommand implements CarCommand {

    /**
     * 执行左转操作
     */
    @Override
    public void execute() {
        System.out.println("小车左转");
    }
}
