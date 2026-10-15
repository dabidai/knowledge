package com.knowledge.service;

import com.knowledge.entity.Document;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentImportMatcherTest {
    Document doc(String id, String dept, boolean pub, String name) {
        return Document.builder().fileId(id).deptName(dept).isPublic(pub).fileName(name).status("expected").build();
    }
    @Test void sameFilenameIsMatchedOnlyInsideTargetScope() {
        var b = doc("b", "B", false, "报告.pdf");
        var a = doc("a", "A", false, "报告.pdf");
        var p = doc("p", "public", true, "报告.pdf");
        assertEquals("a", DocumentImportMatcher.match(List.of(b, p, a), "报告.pdf", "A", false).orElseThrow().getFileId());
        assertEquals("p", DocumentImportMatcher.match(List.of(b, p, a), "报告.pdf", "public", true).orElseThrow().getFileId());
        assertTrue(DocumentImportMatcher.match(List.of(b, p), "报告.pdf", "A", false).isEmpty());
    }
    @Test void ambiguousMatchesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DocumentImportMatcher.match(List.of(
                doc("1", "A", false, "2025/报告.pdf"), doc("2", "A", false, "2026/报告.pdf")), "报告.pdf", "A", false));
    }
    @Test void fuzzyMatchingIsLimitedToExpectedDocumentsAndExactWins() {
        var exact = doc("1", "A", false, "目录\\报告.pdf");
        var fuzzy = doc("2", "A", false, "报告附件.pdf");
        assertEquals("1", DocumentImportMatcher.match(List.of(fuzzy, exact), "报告.pdf", "A", false).orElseThrow().getFileId());
        fuzzy.setStatus("matched");
        assertTrue(DocumentImportMatcher.match(List.of(fuzzy), "报告.pdf", "A", false).isEmpty());
    }
    @Test void existingIdsCannotChangeScope() {
        assertThrows(IllegalArgumentException.class, () -> DocumentImportMatcher.requireSameScope("B", false, "A", false));
        assertThrows(IllegalArgumentException.class, () -> DocumentImportMatcher.requireSameScope("A", true, "A", false));
    }
}

