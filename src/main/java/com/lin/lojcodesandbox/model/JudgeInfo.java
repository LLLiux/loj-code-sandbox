package com.lin.lojcodesandbox.model;

import lombok.Data;

/**
 * 判题信息
 * @author L
 */
@Data
public class JudgeInfo {

    /**
     * 消息
     */
    private String message;

    /**
     * 时间开销（ms）
     */
    private Long time;

    /**
     * 内存开销（kB）
     */
    private Long memory;
}
