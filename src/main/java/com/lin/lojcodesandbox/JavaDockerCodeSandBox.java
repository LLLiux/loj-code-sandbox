package com.lin.lojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lin.lojcodesandbox.enums.ExecuteCodeStatusEnum;
import com.lin.lojcodesandbox.model.ExecuteCodeRequest;
import com.lin.lojcodesandbox.model.ExecuteCodeResponse;
import com.lin.lojcodesandbox.model.ExecuteInfo;
import com.lin.lojcodesandbox.model.JudgeInfo;
import com.lin.lojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author L
 */
public class JavaDockerCodeSandBox implements CodeSandBox {

    private static final String CODE_DIR_NAME = "tmpCode";

    private static final String USER_CODE_NAME = "Main.java";

    private static final Long TIME_LIMIT = 5000L;

    private static final Long MEMORY_LIMIT = 100 * 1000 * 1000L;

    private static Boolean FIRST_LOAD = true;


    public static void main(String[] args) {
        JavaDockerCodeSandBox javaNativeCodeSandBox = new JavaDockerCodeSandBox();
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafe/WriteFile.java", StandardCharsets.UTF_8);
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

        // 3.将编译后的文件上传至容器内
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        // 在已有镜像上扩充
        String openjdkImage = "openjdk:8-alpine";
        if (FIRST_LOAD) {
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("镜像拉取:" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                dockerClient.pullImageCmd(openjdkImage).exec(pullImageResultCallback).awaitCompletion();
            } catch (InterruptedException e) {
                System.out.println("镜像拉取失败");
                throw new RuntimeException(e);
            }
            System.out.println("镜像拉取成功");
            FIRST_LOAD = false;
        }
        // 创建容器
        HostConfig hostConfig = new HostConfig()
                .withBinds(new Bind(userCodeDir, new Volume("/app")))
                .withMemory(MEMORY_LIMIT);
        CreateContainerResponse createContainerResponse = dockerClient.createContainerCmd(openjdkImage)
                .withHostConfig(hostConfig)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        System.out.println(createContainerResponse);

        // 4.容器内执行
        String containerId = createContainerResponse.getId();
        long maxTime = 0L;
        final long[] maxMemory = {0};
        List<String> inputList = executeCodeRequest.getInputList();
        List<String> outputList = new ArrayList<>();
        for (String input : inputList) {
            String[] inputArray = input.split(" ");
            // 创建执行命令（此时还未执行）
            // docker exec xxx java -cp /app Main 1 2
            String[] runCmd = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(runCmd)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.out.println("创建执行命令:" + execCreateCmdResponse);
            // 处理不同类型输出（错误输出表示执行出错 正常输出就是正常返回值）
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        // 错误输出
                        String errorMessage = new String(frame.getPayload());
                        executeCodeResponse.setMessage(errorMessage);
                        executeCodeResponse.setStatus(ExecuteCodeStatusEnum.RUNTIME_ERROR.getValue());
                    } else {
                        // 正常输出
                        String message = new String(frame.getPayload());
                        outputList.add(message);
                    }
                    super.onNext(frame);
                }
            };
            // 执行状态命令（获取占用内存）
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> statisticsResultCallback = new ResultCallback<Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    Long memory = statistics.getMemoryStats().getUsage();
                    System.out.println("内存占用:" + memory);
                    maxMemory[0] = Math.max(maxMemory[0], memory);
                }

                @Override
                public void close() {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            };
            statsCmd.exec(statisticsResultCallback);
            // 执行执行命令
            String execId = execCreateCmdResponse.getId();
            try {
                // 计时
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_LIMIT, TimeUnit.MICROSECONDS);
                stopWatch.stop();
                maxTime = Math.max(maxTime, stopWatch.getLastTaskTimeMillis());
                statsCmd.close();
                // 超时 或 超出内存 或 运行错误 则终止循环
                if (maxTime > TIME_LIMIT || maxMemory[0] > MEMORY_LIMIT
                        || ExecuteCodeStatusEnum.RUNTIME_ERROR.getValue().equals(executeCodeResponse.getStatus())) {
                    break;
                }
            } catch (InterruptedException e) {
                // 系统错误 直接返回
                executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
                return executeCodeResponse;
            }

        }

        // 5.得到结果并整理
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory[0]);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        // 6.清理文件
        if (FileUtil.exist(userCodeDir)) {
            FileUtil.del(userCodeDir);
        }

        return executeCodeResponse;
    }
}