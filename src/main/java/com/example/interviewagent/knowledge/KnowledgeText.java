package com.example.interviewagent.knowledge;

import com.example.interviewagent.exception.InvalidRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 简单可解释的字符分块；偏移是 Java/浏览器共同使用的 UTF-16 下标，[start,end)。 */
public final class KnowledgeText {
    public static final int CHUNK_SIZE = 800;
    public static final int OVERLAP = 100;
    private KnowledgeText() {}
    public record Part(int position, int start, int end, String content) {}

    public static String normalize(String content) {
        if (content == null) throw new InvalidRequestException("资料正文不能为空");
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank() || normalized.length() > 6000 || normalized.indexOf('\0') >= 0)
            throw new InvalidRequestException("资料正文需要 1～6000 字符，不能包含空字符");
        return normalized;
    }

    public static List<Part> split(String content) {
        var parts = new ArrayList<Part>();
        for (int start = 0; start < content.length();) {
            int end = Math.min(content.length(), start + CHUNK_SIZE);
            // 不把 emoji 等补充字符的代理对截成两半。
            if (end < content.length() && Character.isLowSurrogate(content.charAt(end))) end--;
            String text = content.substring(start, end);
            if (!text.isBlank()) parts.add(new Part(parts.size() + 1, start, end, text));
            if (end == content.length()) break;
            start = end - OVERLAP;
            if (Character.isLowSurrogate(content.charAt(start))) start--;
        }
        return List.copyOf(parts);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public static byte[] encode(float[] vector) {
        var buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length % Float.BYTES != 0) throw new IllegalStateException("向量存储格式无效");
        var buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) throw new IllegalStateException("向量维度不一致");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) throw new IllegalStateException("向量包含无效数值");
            dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i];
        }
        if (aa == 0 || bb == 0) throw new IllegalStateException("无效的零向量");
        return Math.max(-1, Math.min(1, dot / Math.sqrt(aa * bb)));
    }
}
