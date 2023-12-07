package com.lin.lojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import com.lin.lojcodesandbox.enums.ExecuteCodeStatusEnum;
import com.lin.lojcodesandbox.model.ExecuteCodeRequest;
import com.lin.lojcodesandbox.model.ExecuteCodeResponse;
import com.lin.lojcodesandbox.model.ExecuteInfo;
import com.lin.lojcodesandbox.model.JudgeInfo;
import com.lin.lojcodesandbox.utils.ProcessUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author L
 */
public class JavaNativeCodeSandBox implements CodeSandBox {

    private final String CODE_DIR_NAME = "tmpCode";

    private final String USER_CODE_NAME = "Main.java";

    public static void main(String[] args) {
        JavaNativeCodeSandBox javaNativeCodeSandBox = new JavaNativeCodeSandBox();
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code)
                .language("java")
                .inputList(Arrays.asList("1 2", "1 3"))
                .build();
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandBox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();

        // 1.将用户传入代码保存为 java 文件
        // 检查代码存储目录是否存在
        String userDir = System.getProperty("user.dir");
        String codeDir = userDir + File.separator + CODE_DIR_NAME;
        if (!FileUtil.exist(codeDir)) {
            FileUtil.mkdir(codeDir);
        }
        // 对于每次代码提交 生成独立目录（存 .java 和 .class）
        String userCodeDir = codeDir + File.separator + UUID.randomUUID();
        String userCodePath = userCodeDir + File.separator + USER_CODE_NAME;
        String code = executeCodeRequest.getCode();
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);

        // 2.编译
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodePath);
        ExecuteInfo compileInfo;
        try {
            compileInfo = ProcessUtils.runAndGetInfo(compileCmd, "编译");
        } catch (Exception e) {
            // 系统错误
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
            return executeCodeResponse;
        }
        System.out.println(compileInfo);
        String compileErrorMessage = compileInfo.getErrorMessage();
        if (StrUtil.isNotBlank(compileErrorMessage)) {
            executeCodeResponse.setMessage(compileErrorMessage);
            // 编译错误
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.COMPILE_ERROR.getValue());
            return executeCodeResponse;
        }

        // 3.执行
        List<ExecuteInfo> runInfoList = new ArrayList<>();
        List<String> inputList = executeCodeRequest.getInputList();
        for (String input : inputList) {
            String runCmd = String.format("java -Dfile.encoding=utf-8 -cp %s Main %s", userCodeDir, input);
            ExecuteInfo runInfo;
            try {
                runInfo = ProcessUtils.runAndGetInfo(runCmd, "运行");
            } catch (Exception e) {
                // 系统错误
                executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
                return executeCodeResponse;
            }
            System.out.println(runInfo);
            runInfoList.add(runInfo);
        }

        // 4.得到结果并整理
        List<String> outputList = new ArrayList<>();
        long maxTime = 0L;
        for (ExecuteInfo runInfo : runInfoList) {
            String runErrorMessage = runInfo.getErrorMessage();
            if (StrUtil.isNotBlank(runErrorMessage)) {
                executeCodeResponse.setMessage(runErrorMessage);
                // 运行错误
                executeCodeResponse.setStatus(ExecuteCodeStatusEnum.RUNTIME_ERROR.getValue());
                break;
            }
            outputList.add(runInfo.getMessage());
            Long time = runInfo.getTime();
            maxTime = Math.max(maxTime, time);
        }
        executeCodeResponse.setOutputList(outputList);

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // todo
        // judgeInfo.setMemory();
        executeCodeResponse.setJudgeInfo(judgeInfo);
        executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SUCCEED.getValue());

        // 5.清理文件
        if (FileUtil.exist(userCodeDir)) {
            FileUtil.del(userCodeDir);
        }

        return executeCodeResponse;
    }
}