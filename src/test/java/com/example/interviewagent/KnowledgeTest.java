package com.example.interviewagent;

import com.example.interviewagent.ai.*;
import com.example.interviewagent.knowledge.*;
import com.example.interviewagent.exception.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTest {
    @Test
    void chunksCoverOriginalAndPreserveSurrogatePairsWithBoundedBatch() {
        String content = "学".repeat(799) + "😀" + "Java\n".repeat(1039);
        var parts = KnowledgeText.split(content);
        assertTrue(parts.size() <= 9);
        assertEquals(0, parts.getFirst().start());
        assertEquals(content.length(), parts.getLast().end());
        int previousEnd = 0;
        for (var part : parts) {
            assertTrue(part.start() <= previousEnd);
            assertEquals(content.substring(part.start(), part.end()), part.content());
            assertFalse(Character.isLowSurrogate(part.content().charAt(0)));
            assertFalse(Character.isHighSurrogate(part.content().charAt(part.content().length() - 1)));
            assertTrue(part.content().length() <= 800);
            previousEnd = part.end();
        }
    }

    @Test
    void normalizedDocumentsDeduplicateLineEndingsAndRejectInvalidInput() {
        assertEquals(KnowledgeText.sha256(KnowledgeText.normalize(" a\r\nb ")),
                KnowledgeText.sha256(KnowledgeText.normalize("a\nb")));
        assertThrows(InvalidRequestException.class, () -> KnowledgeText.normalize(" \n "));
        assertThrows(InvalidRequestException.class, () -> KnowledgeText.normalize("x".repeat(6001)));
        assertThrows(InvalidRequestException.class, () -> KnowledgeText.normalize("x\0x"));
    }

    @Test
    void cosineUsesDirectionAndBinaryRoundTripPreservesValues() {
        float[] vector = {1, 2, -3};
        assertArrayEquals(vector, KnowledgeText.decode(KnowledgeText.encode(vector)));
        assertEquals(1, KnowledgeText.cosine(new float[]{1, 2}, new float[]{3, 6}), 1e-9);
        assertEquals(0, KnowledgeText.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertThrows(IllegalStateException.class, () -> KnowledgeText.cosine(new float[]{1}, new float[]{1, 2}));
        assertThrows(IllegalStateException.class, () -> KnowledgeText.cosine(new float[]{0}, new float[]{1}));
        assertThrows(IllegalStateException.class, () -> KnowledgeText.cosine(new float[]{Float.NaN}, new float[]{1}));
    }

    private static final String FIELDS = "\"assessment\":\"基本准确\",\"strengths\":[],\"improvements\":[\"补充定位开销\"],\"followUpQuestion\":\"如何选择集合？\"";
    private final AiFeedbackEvaluator evaluator = new AiFeedbackEvaluator(null, null);
    private final KnowledgeService.Hit hit = new KnowledgeService.Hit("K7", 2, "集合笔记", 1, 0, 10, .8, "链表需要先定位节点。");

    @Test
    void citationsOnlyUseRetrievedIdsAndServerSuppliedSnapshot() {
        String raw = "{" + FIELDS + ",\"citations\":[{\"sourceId\":\"K7\",\"supports\":\"不能忽略定位开销\"}]}";
        String feedback = evaluator.validateAndFormat(raw, List.of(hit));
        assertTrue(feedback.contains("资料 #2"));
        assertTrue(feedback.contains("链表需要先定位节点。"));
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat(raw.replace("K7", "K999"), List.of(hit)));
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat(raw, List.of()));
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat(raw.replace("\"supports\":", "\"extra\":1,\"supports\":"), List.of(hit)));
    }

    @Test
    void noEvidenceIsExplicitAndDuplicateCitationsAreRejected() {
        assertTrue(evaluator.validateAndFormat("{" + FIELDS + ",\"citations\":[]}", List.of()).contains("本次没有引用知识库资料"));
        String citation = "{\"sourceId\":\"K7\",\"supports\":\"定位开销\"}";
        assertThrows(AiCallException.class, () -> evaluator.validateAndFormat("{" + FIELDS + ",\"citations\":[" + citation + "," + citation + "]}", List.of(hit)));
    }
}
