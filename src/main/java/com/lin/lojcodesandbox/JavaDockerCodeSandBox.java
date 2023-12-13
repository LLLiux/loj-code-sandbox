package com.lin.lojcodesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.InvocationBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lin.lojcodesandbox.enums.ExecuteCodeStatusEnum;
import com.lin.lojcodesandbox.model.ExecuteCodeRequest;
import com.lin.lojcodesandbox.model.ExecuteCodeResponse;
import com.lin.lojcodesandbox.model.ExecuteInfo;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author L
 */
@Component
public class JavaDockerCodeSandBox extends JavaCodeSandBoxTemplate {

    private static Boolean FIRST_LOAD = true;

    public static void main(String[] args) {
        JavaDockerCodeSandBox javaNativeCodeSandBox = new JavaDockerCodeSandBox();
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafe/WriteFile.java", StandardCharsets.UTF_8);
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code)
                .language("java")
                .inputList(Arrays.asList("1 2", "1 3", "2 4", "1 7"))
                .build();
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandBox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    List<ExecuteInfo> runCode(List<String> inputList, File codeFile, ExecuteCodeResponse executeCodeResponse) throws Exception {
        // 1.将编译后的文件上传至容器内
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
        String userCodeDir = codeFile.getParentFile().getAbsolutePath();
        HostConfig hostConfig = new HostConfig()
                .withBinds(new Bind(userCodeDir, new Volume("/app")))
                .withMemory(MEMORY_LIMIT)
                .withMemorySwap(0L);
        CreateContainerResponse createContainerResponse = dockerClient.createContainerCmd(openjdkImage)
                .withHostConfig(hostConfig)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withTty(true)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .exec();
        System.out.println(createContainerResponse);

        // 2.容器内执行
        // 启动容器
        String containerId = createContainerResponse.getId();
        dockerClient.startContainerCmd(containerId).exec();
        // 执行
        List<ExecuteInfo> runInfoList = new ArrayList<>();
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
            final String[] message = new String[1];
            final String[] errorMessage = new String[1];
            final boolean[] getMessageFlag = {false};
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    if(!getMessageFlag[0]){
                        StreamType streamType = frame.getStreamType();
                        if (StreamType.STDERR.equals(streamType)) {
                            // 错误输出
                            errorMessage[0] = new String(frame.getPayload());
                            System.out.println("输出错误结果:" + errorMessage[0]);
                        } else {
                            // 正常输出
                            message[0] = new String(frame.getPayload());
                            System.out.println("输出结果:" + message[0]);
                        }
                        getMessageFlag[0] = true;
                    }
                    super.onNext(frame);
                }

            };
            // 执行状态命令（获取占用内存）
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ExecuteInfo runInfo = new ExecuteInfo();
            final long[] memory = new long[1];
            InvocationBuilder.AsyncResultCallback<Statistics> callback = new InvocationBuilder.AsyncResultCallback<Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用:" + statistics.getMemoryStats().getUsage());
                    memory[0] = statistics.getMemoryStats().getUsage();
                }
            };
            // 执行执行命令
            String execId = execCreateCmdResponse.getId();
            long time;
            // 计时
            StopWatch stopWatch = new StopWatch();
            statsCmd.withNoStream(true).exec(callback).awaitResult();
            stopWatch.start();
            dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(TIME_LIMIT, TimeUnit.MILLISECONDS);
            stopWatch.stop();
            // 封装执行信息
            time = stopWatch.getLastTaskTimeMillis();
            runInfo.setTime(time);
            runInfo.setMemory(memory[0]);
            runInfo.setMessage(message[0]);
            runInfo.setErrorMessage(errorMessage[0]);
            System.out.println(runInfo);
            runInfoList.add(runInfo);
            // 超时 或 超出内存则终止循环
            if (runInfo.getTime() > TIME_LIMIT || runInfo.getMemory() > MEMORY_LIMIT) {
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
        return runInfoList;
    }
}
