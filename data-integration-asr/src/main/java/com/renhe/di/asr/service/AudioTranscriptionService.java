package com.renhe.di.asr.service;

/**
 * 语音转文字服务接口
 * <p>
 * 将音频数据（字节数组）转换为文本。
 * 支持多种实现：Whisper API、本地 sherpa-onnx 等。
 */
public interface AudioTranscriptionService {

    /**
     * 将音频字节数据转写为文本
     *
     * @param audioData  音频文件的字节数据（AMR/Silk/MP3/WAV 等格式）
     * @param fileName   音频文件名（含扩展名，用于判断格式），如 "voice.amr"
     * @return 转写后的文本，失败时返回 null
     */
    String transcribe(byte[] audioData, String fileName);

    /**
     * 当前转写服务是否可用
     */
    boolean isAvailable();
}
