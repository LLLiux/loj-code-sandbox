package com.lin.lojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.lin.lojcodesandbox.enums.ExecuteCodeStatusEnum;
import com.lin.lojcodesandbox.model.ExecuteCodeRequest;
import com.lin.lojcodesandbox.model.ExecuteCodeResponse;
import com.lin.lojcodesandbox.model.ExecuteInfo;
import com.lin.lojcodesandbox.model.JudgeInfo;
import com.lin.lojcodesandbox.utils.ProcessUtils;

import java.io.File;
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

    private final Long TIME_LIMIT = 5000L;

    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final WordTree BLACK_LIST_WORD_TREE = new WordTree();

    static {
        BLACK_LIST_WORD_TREE.addWords(blackList);
    }

    public static void main(String[] args) {
        JavaNativeCodeSandBox javaNativeCodeSandBox = new JavaNativeCodeSandBox();
//        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        String code = ResourceUtil.readStr("testCode/unsafe/ReadFile.java", StandardCharsets.UTF_8);
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
        executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SUCCEED.getValue());
        String code = executeCodeRequest.getCode();
        // 通过黑名单限制操作
        FoundWord foundWord = BLACK_LIST_WORD_TREE.matchWord(code);
        if (foundWord != null) {
            System.out.println("包含禁止词:" + foundWord);
            return null;
        }

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
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);

        // 2.编译
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodePath);
        ExecuteInfo compileInfo;
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            compileInfo = ProcessUtils.runProcessAndGetInfo(compileProcess, "编译");
        } catch (Exception e) {
            // 系统错误 直接返回
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
            return executeCodeResponse;
        }
        System.out.println(compileInfo);
        String compileErrorMessage = compileInfo.getErrorMessage();
        if (StrUtil.isNotBlank(compileErrorMessage)) {
            executeCodeResponse.setMessage(compileErrorMessage);
            // 编译错误 直接返回
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.COMPILE_ERROR.getValue());
            return executeCodeResponse;
        }

        // 3.执行
        List<ExecuteInfo> runInfoList = new ArrayList<>();
        List<String> inputList = executeCodeRequest.getInputList();
        for (String input : inputList) {
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=utf-8 -cp %s Main %s", userCodeDir, input);
            ExecuteInfo runInfo;
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_LIMIT);
                        // 如果执行进程还没结束则中断
                        try {
                            runProcess.exitValue();
                        } catch (Exception e) {
                            System.out.println("超时了，中断");
                            runProcess.destroy();
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                runInfo = ProcessUtils.runProcessAndGetInfo(runProcess, "运行");
            } catch (Exception e) {
                // 系统错误 直接返回
                executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
                return executeCodeResponse;
            }
            System.out.println(runInfo);
            runInfoList.add(runInfo);
            // 超时则终止循环
            if (runInfo.getTime() > TIME_LIMIT) {
                break;
            }
            // 运行错误则终止循环
            String runErrorMessage = runInfo.getErrorMessage();
            if (StrUtil.isNotBlank(runErrorMessage)) {
                executeCodeResponse.setMessage(runErrorMessage);
                // 运行错误
                executeCodeResponse.setStatus(ExecuteCodeStatusEnum.RUNTIME_ERROR.getValue());
                break;
            }
        }

        // 4.得到结果并整理
        List<String> outputList = new ArrayList<>();
        long maxTime = 0L;
        for (ExecuteInfo runInfo : runInfoList) {
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

        // 5.清理文件
        if (FileUtil.exist(userCodeDir)) {
            FileUtil.del(userCodeDir);
        }

        return executeCodeResponse;
    }
}
