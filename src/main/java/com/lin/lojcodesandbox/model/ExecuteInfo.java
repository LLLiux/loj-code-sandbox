package com.lin.lojcodesandbox.model;

import lombok.Data;

/**
 * 执行信息
 * @author L
 */
@Data
public class ExecuteInfo {
    /**
     * 消息
     */
    private String message;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 时间开销（ms）
     */
    private Long time;

    /**
     * 内存开销（kB）
     */
    private Long memory;


}
