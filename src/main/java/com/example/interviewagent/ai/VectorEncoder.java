package com.example.interviewagent.ai;

import java.util.List;

/** 检索只依赖向量能力，测试可替换为确定性向量，不消耗真实 API 额度。 */
public interface VectorEncoder {
    List<float[]> embed(List<String> texts);
    String embeddingIdentity();
}
