package com.knowledge.service;

import com.knowledge.entity.Document;
import java.util.*;

/** 同名覆盖只能发生在同一归属范围内；歧义匹配直接拒绝。 */
public final class DocumentImportMatcher {
    private DocumentImportMatcher() {}

    public static boolean sameScope(String existingDept, Boolean existingPublic, String dept, boolean isPublic) {
        return Objects.equals(existingDept, dept) && Objects.equals(existingPublic, isPublic);
    }

    public static void requireSameScope(String existingDept, Boolean existingPublic, String dept, boolean isPublic) {
        if (!sameScope(existingDept, existingPublic, dept, isPublic)) {
            throw new IllegalArgumentException("资源 ID 已属于其他部门或公共范围，禁止跨范围覆盖");
        }
    }

    public static Optional<Document> match(List<Document> candidates, String name, String dept, boolean isPublic) {
        List<Document> scoped = candidates.stream()
                .filter(d -> sameScope(d.getDeptName(), d.getIsPublic(), dept, isPublic)).toList();
        List<Document> exact = scoped.stream().filter(d -> base(d.getFileName()).equals(base(name))).toList();
        if (!exact.isEmpty()) return unique(exact);
        String base = base(name);
        if (base.isBlank()) return Optional.empty();
        return unique(scoped.stream().filter(d -> "expected".equals(d.getStatus()))
                .filter(d -> !base(d.getFileName()).isBlank())
                .filter(d -> base.contains(base(d.getFileName())) || base(d.getFileName()).contains(base)).toList());
    }

    private static Optional<Document> unique(List<Document> matches) {
        if (matches.size() > 1) throw new IllegalArgumentException("同部门存在多个同名或近似文件，请明确文件映射后重试");
        return matches.stream().findFirst();
    }

    private static String base(String name) {
        if (name == null) return "";
        String leaf = name.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }
}
