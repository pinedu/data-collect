package com.renhe.di.asr.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地语音转文字服务（whisper.cpp）
 * <p>
 * 纯本地实现，不依赖任何第三方 API。
 * 首次启动自动下载 whisper.cpp 可执行文件和中文模型，缓存到本地目录，后续启动不再下载。
 * <p>
 * 工作原理：
 * 1. 将音频数据写入临时 WAV 文件
 * 2. 调用 whisper.cpp 子进程执行离线识别
 * 3. 解析输出获取转写文本
 * <p>
 * 所需文件（自动下载，仅首次）：
 * - whisper-bin-x64.zip（~5MB）：whisper.cpp Windows x64 可执行文件
 * - ggml-small.bin（~460MB）：中文语音识别模型
 */
@Slf4j
@Service
public class LocalWhisperTranscriber implements AudioTranscriptionService {

    /**
     * 本地缓存根目录（默认放在当前模块下的 models/ 目录）
     */
    @Value("${asr.local.cache-dir:}")
    private String cacheDir;

    /**
     * whisper.cpp Windows x64 可执行文件下载地址（zip 包）
     */
    @Value("${asr.local.whisper-zip-url:https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.6/whisper-bin-x64.zip}")
    private String whisperZipUrl;

    /**
     * 中文模型下载地址（small 模型，460MB，中文效果好）
     */
    @Value("${asr.local.model-url:https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin}")
    private String modelUrl;

    /**
     * 模型文件名
     */
    @Value("${asr.local.model-name:ggml-small.bin}")
    private String modelName;

    /**
     * 识别超时（秒）
     */
    @Value("${asr.local.timeout-seconds:60}")
    private int timeoutSeconds;

    private Path whisperExePath;
    private Path modelPath;
    private boolean ready = false;

    // TODO: 自动下载功能暂时禁用，当前使用预置的 models 目录文件
    // @PostConstruct
    public void init() {
        try {
            // 默认缓存到模块目录下的 models/
            Path cachePath;
            if (cacheDir != null && !cacheDir.isEmpty()) {
                cachePath = Paths.get(cacheDir);
            } else {
                cachePath = Paths.get("data-integration-asr", "models");
            }
            Files.createDirectories(cachePath);

            whisperExePath = cachePath.resolve("whisper-cli.exe");
            modelPath = cachePath.resolve(modelName);

            // // 检查可执行文件
            // if (!Files.exists(whisperExePath)) {
            //     log.info("首次启动，下载 whisper.cpp 可执行文件...");
            //     downloadAndExtractWhisper(whisperZipUrl, cachePath);
            //     log.info("whisper.cpp 下载完成: {}", whisperExePath);
            // }

            // // 检查模型文件
            // if (!Files.exists(modelPath)) {
            //     log.info("首次启动，下载中文语音模型（约460MB，仅下载一次）...");
            //     downloadFile(modelUrl, modelPath);
            //     log.info("模型下载完成: {}", modelPath);
            // }

            // 仅检查文件是否存在，不自动下载
            if (!Files.exists(whisperExePath) || !Files.exists(modelPath)) {
                log.warn("ASR文件缺失: exe={}, model={}，请手动放置到 {} 目录",
                        whisperExePath, modelPath, cachePath);
                ready = false;
                return;
            }

            ready = true;
            log.info("本地语音转文字服务就绪: exe={}, model={}", whisperExePath, modelPath);

        } catch (Exception e) {
            log.error("本地语音转文字服务初始化失败: {}", e.getMessage(), e);
            ready = false;
        }
    }

    @Override
    public String transcribe(byte[] audioData, String fileName) {
        if (!ready) {
            log.warn("本地ASR服务未就绪");
            return null;
        }

        Path tempWav = null;
        try {
            // whisper.cpp 需要 16kHz WAV 格式
            tempWav = Files.createTempFile("asr_", ".wav");
            Files.write(tempWav, audioData);

            log.info("开始本地语音识别: fileSize={}KB", audioData.length / 1024);

            // 调用 whisper.cpp 子进程
            ProcessBuilder pb = new ProcessBuilder(
                    whisperExePath.toString(),
                    "-m", modelPath.toString(),
                    "-f", tempWav.toString(),
                    "-l", "zh",           // 中文
                    "-nt",                // 不输出时间戳
                    "--no-prints"         // 不打印调试信息
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("语音识别超时 ({}s)", timeoutSeconds);
                return null;
            }

            if (process.exitValue() != 0) {
                log.warn("whisper.cpp 退出码: {}, 输出: {}", process.exitValue(), output);
                return null;
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                log.warn("语音识别结果为空");
                return null;
            }

            log.info("本地语音识别完成: textLength={}, preview={}",
                    result.length(),
                    result.length() > 60 ? result.substring(0, 60) + "..." : result);

            return result;

        } catch (Exception e) {
            log.error("本地语音识别失败: {}", e.getMessage(), e);
            return null;
        } finally {
            // 清理临时文件
            if (tempWav != null) {
                try {
                    Files.deleteIfExists(tempWav);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return ready;
    }

    /**
     * 下载并解压 whisper.cpp zip 包
     * <p>
     * zip 包内根目录为 Release/，解压时跳过该前缀，直接放到 targetDir 下
     */
    private void downloadAndExtractWhisper(String zipUrl, Path targetDir) throws IOException {
        Path tempZip = Files.createTempFile("whisper_", ".zip");
        try {
            log.info("下载 whisper.cpp: {} → {}", zipUrl, tempZip);
            downloadFile(zipUrl, tempZip);

            log.info("解压 whisper.cpp 到: {}", targetDir);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    // 跳过 zip 包内的根目录前缀（如 Release/）
                    if (entryName.contains("/")) {
                        entryName = entryName.substring(entryName.indexOf('/') + 1);
                    }
                    if (entryName.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }

                    Path entryPath = targetDir.resolve(entryName);
                    // 防止 zip slip 攻击
                    if (!entryPath.normalize().startsWith(targetDir.normalize())) {
                        throw new IOException("Zip entry outside target dir: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                        log.debug("解压: {}", entryPath.getFileName());
                    }
                    zis.closeEntry();
                }
            }

            long sizeMB = Files.size(tempZip) / (1024 * 1024);
            log.info("whisper.cpp 解压完成 (zip大小: {}MB)", sizeMB);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    /**
     * 下载文件到本地
     */
    private void downloadFile(String urlStr, Path target) throws IOException {
        URL url = new URL(urlStr);
        log.info("开始下载: {} → {}", urlStr, target);

        try (InputStream in = url.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        long sizeMB = Files.size(target) / (1024 * 1024);
        log.info("下载完成: {} ({}MB)", target.getFileName(), sizeMB);
    }
}
