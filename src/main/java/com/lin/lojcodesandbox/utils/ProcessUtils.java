package com.lin.lojcodesandbox.utils;

import com.lin.lojcodesandbox.model.ExecuteInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StopWatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * @author L
 */
public class ProcessUtils {

    public static ExecuteInfo runAndGetInfo(String cmd, String opName) throws Exception {
        ExecuteInfo executeInfo = new ExecuteInfo();
        StopWatch stopWatch = new StopWatch();
        Process process = Runtime.getRuntime().exec(cmd);
        stopWatch.start();
        int exitValue = process.waitFor();
        // 获取编译过程输出到控制台的消息
        if (exitValue == 0) {
            System.out.println(opName + "成功");
            String message = getMessageFromStream(process.getInputStream());
            executeInfo.setMessage(message);
        } else {
            System.out.println(opName + "失败，错误码:" + exitValue);
            String message = getMessageFromStream(process.getInputStream());
            executeInfo.setMessage(message);

            String errorMessage = getMessageFromStream(process.getErrorStream());
            executeInfo.setErrorMessage(errorMessage);
        }
        stopWatch.stop();
        executeInfo.setTime(stopWatch.getLastTaskTimeMillis());
        return executeInfo;
    }

    private static String getMessageFromStream(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        List<String> compileOutputList = new ArrayList<>();
        String compileOutputLine;
        while ((compileOutputLine = bufferedReader.readLine()) != null) {
            compileOutputList.add(compileOutputLine);
        }
        return StringUtils.join(compileOutputList, "\n");
    }
}
