package com.lin.lojcodesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import com.lin.lojcodesandbox.enums.ExecuteCodeStatusEnum;
import com.lin.lojcodesandbox.model.ExecuteCodeRequest;
import com.lin.lojcodesandbox.model.ExecuteCodeResponse;
import com.lin.lojcodesandbox.model.ExecuteInfo;
import com.lin.lojcodesandbox.utils.ProcessUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author L
 */
@Component
public class JavaNativeCodeSandBox extends JavaCodeSandBoxTemplate {
    public static void main(String[] args) {
        JavaNativeCodeSandBox javaNativeCodeSandBox = new JavaNativeCodeSandBox();
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafe/WriteFile.java", StandardCharsets.UTF_8);
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code)
                .language("java")
                .inputList(Arrays.asList("1 2", "1 3", "5 6"))
                .build();
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandBox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    protected List<ExecuteInfo> runCode(List<String> inputList, File codeFile, ExecuteCodeResponse executeCodeResponse) throws Exception {
        List<ExecuteInfo> runInfoList = new ArrayList<>();
//        String securityManagerDir = userDir + File.separator + SECURITY_MANAGER_DIR;
        String userCodeDir = codeFile.getParentFile().getAbsolutePath();
        for (String input : inputList) {
            // java -Xmx256m -Dfile.encoding=utf-8 -cp %s;%s -Djava.security.manager=%s Main %s
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=utf-8 -cp %s Main %s", userCodeDir, input);
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
            ExecuteInfo runInfo = ProcessUtils.runProcessAndGetInfo(runProcess, "运行");
            // 较难实现 此处仅为跑通流程
            runInfo.setMemory(0L);
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
        return runInfoList;
    }
}
