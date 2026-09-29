package com.carbackend.core;

/**
 * 前进指令
 */
public class ForwardCommand implements CarCommand {

    /**
     * 执行前进操作
     */
    @Override
    public void execute() {
        System.out.println("小车前进");
    }
}
