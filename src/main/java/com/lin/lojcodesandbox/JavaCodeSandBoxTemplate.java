package com.lin.lojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.lin.lojcodesandbox.enums.ExecuteCodeStatusEnum;
import com.lin.lojcodesandbox.model.ExecuteCodeRequest;
import com.lin.lojcodesandbox.model.ExecuteCodeResponse;
import com.lin.lojcodesandbox.model.ExecuteInfo;
import com.lin.lojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public abstract class JavaCodeSandBoxTemplate implements CodeSandBox {

    protected static final String CODE_DIR_NAME = "tmpCode";

    protected static final String USER_CODE_NAME = "Main.java";

    protected static final Long TIME_LIMIT = 5000L;

    protected static final Long MEMORY_LIMIT = 100 * 1000 * 1000L;

    protected static final String SECURITY_MANAGER_DIR = "security";

    protected static final String SECURITY_MANAGER_NAME = "MySecurityManager";

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SUCCEED.getValue());

        // 1.将用户传入代码保存为 java 文件
        String code = executeCodeRequest.getCode();
        File codeFile = saveCode(code);

        // 2.编译（系统错误 或 编译错误 直接返回）
        ExecuteInfo compileInfo;
        try {
            compileInfo = compileCode(codeFile);
        } catch (Exception e) {
            // 系统错误 直接返回
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
            return executeCodeResponse;
        }

        String compileErrorMessage = compileInfo.getErrorMessage();
        if (StrUtil.isNotBlank(compileErrorMessage)) {
            executeCodeResponse.setMessage(compileErrorMessage);
            // 编译错误 直接返回
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.COMPILE_ERROR.getValue());
            return executeCodeResponse;
        }

        // 3.执行（系统错误 直接返回）
        List<ExecuteInfo> runInfoList;
        List<String> inputList = executeCodeRequest.getInputList();
        try {
            runInfoList = runCode(inputList, codeFile, executeCodeResponse);
        } catch (Exception e) {
            // 系统错误 直接返回
            executeCodeResponse.setStatus(ExecuteCodeStatusEnum.SYSTEM_ERROR.getValue());
            return executeCodeResponse;
        }

        // 4.处理运行结果
        handleRunInfo(runInfoList, executeCodeResponse);

        // 5.清理文件
        boolean b = deleteFile(codeFile);
        if (!b) {
            log.error("deleteFile error,file path : {}", codeFile.getAbsolutePath());
        }

        return executeCodeResponse;
    }

    /**
     * 保存代码
     *
     * @param code
     * @return
     */
    private File saveCode(String code) {
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
        return userCodeFile;
    }

    /**
     * 编译代码
     *
     * @param codeFile
     * @return
     * @throws Exception
     */
    private ExecuteInfo compileCode(File codeFile) throws Exception {
        String userCodePath = codeFile.getAbsolutePath();
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodePath);
        ExecuteInfo compileInfo;
        Process compileProcess = Runtime.getRuntime().exec(compileCmd);
        compileInfo = ProcessUtils.runProcessAndGetInfo(compileProcess, "编译");
        System.out.println(compileInfo);
        return compileInfo;
    }

    /**
     * 运行代码
     *
     * @param inputList
     * @param codeFile
     * @return
     * @throws Exception
     */
    abstract List<ExecuteInfo> runCode(List<String> inputList, File codeFile, ExecuteCodeResponse executeCodeResponse) throws Exception;

    /**
     * 处理运行结果
     *
     * @param runInfoList
     * @param executeCodeResponse
     */
    private void handleRunInfo(List<ExecuteInfo> runInfoList, ExecuteCodeResponse executeCodeResponse) {
        List<String> outputList = new ArrayList<>();
        long maxTime = 0L;
        long maxMemory = 0L;
        for (ExecuteInfo runInfo : runInfoList) {
            outputList.add(runInfo.getMessage());
            maxTime = Math.max(maxTime, runInfo.getTime());
            maxMemory = Math.max(maxMemory, runInfo.getMemory());
        }
        executeCodeResponse.setOutputList(outputList);

        executeCodeResponse.setTime(maxTime);
        executeCodeResponse.setMemory(maxMemory);
    }

    /**
     * 删除文件
     *
     * @param codeFile
     * @return
     */
    private boolean deleteFile(File codeFile) {
        String userCodeDir = codeFile.getParentFile().getAbsolutePath();
        if (FileUtil.exist(userCodeDir)) {
            boolean del = FileUtil.del(userCodeDir);
            return del;
        }
        return true;
    }
}
