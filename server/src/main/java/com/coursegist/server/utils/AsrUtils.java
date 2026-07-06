package com.coursegist.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class AsrUtils {

    @Value("${ai.llm.api-key}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return "❌ 错误：音频文件不存在";

        String url = "https://api.siliconflow.cn/v1/audio/transcriptions";
        int maxRetries = 3;
        String lastError = "";

        for (int i = 0; i < maxRetries; i++) {
            try {
                System.out.println("[asr] 提交转写请求, attempt=" + (i + 1));

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(),
                                RequestBody.create(file, MediaType.parse("application/octet-stream")))
                        .addFormDataPart("model", "TeleAI/TeleSpeechASR")
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String resultJson = response.body().string();
                        JSONObject jsonObject = JSON.parseObject(resultJson);
                        if (jsonObject.containsKey("text")) {
                            return jsonObject.getString("text");
                        }
                    } else {
                        // 5xx 视为服务端瞬时故障，按指数退避重试
                        String errBody = response.body() != null ? response.body().string() : "";
                        lastError = "HTTP " + response.code() + ": " + errBody;
                        System.err.println("[asr] 转写失败 (" + (i + 1) + "/" + maxRetries + "): " + lastError);

                        if (response.code() >= 500) {
                            if (i < maxRetries - 1 && !waitBeforeRetry(i)) {
                                return "❌ 识别任务已中断";
                            }
                            continue;
                        } else {
                            // 4xx 属于请求本身的问题，重试没有意义
                            return "❌ 识别失败: " + lastError;
                        }
                    }
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                System.err.println("[asr] 网络异常 (" + (i + 1) + "/" + maxRetries + "): " + lastError);
                if (i < maxRetries - 1 && !waitBeforeRetry(i)) {
                    return "❌ 识别任务已中断";
                }
            }
        }

        return "❌ 最终失败 (重试3次): " + lastError;
    }

    private boolean waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryDelayMillis(attempt));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static long retryDelayMillis(int attempt) {
        return 2000L * (1L << attempt);
    }
}
