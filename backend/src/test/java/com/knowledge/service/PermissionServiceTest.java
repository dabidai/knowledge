package com.knowledge.service;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import com.knowledge.security.AccessScope;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionServiceTest {
    final DocumentRepository docs = mock(DocumentRepository.class);
    final ItemRepository items = mock(ItemRepository.class);
    final PermissionService permissions = new PermissionService(docs, items);

    static User user(String role, String dept) {
        return User.builder().id(1L).role(role).enabled(true)
                .department(dept == null ? null : Department.builder().name(dept).build()).build();
    }
    static Document doc(String id, String dept, boolean pub) {
        return Document.builder().fileId(id).fileName(id + ".pdf").deptName(dept).isPublic(pub).build();
    }

    @Test void roleDepartmentAndPublicMatrix() {
        AccessScope member = AccessScope.of(user("default", "A"));
        assertTrue(member.allows("A", false));
        assertTrue(member.allows("B", true));
        assertFalse(member.allows("B", false));
        assertFalse(member.allows(null, null));
        AccessScope noDepartment = AccessScope.of(user("default", null));
        assertTrue(noDepartment.allows("public", true));
        assertFalse(noDepartment.allows(null, false));
        assertFalse(noDepartment.allows("A", false));
        assertTrue(AccessScope.of(user("admin", null)).allows("B", false));
        User disabled = user("admin", "A"); disabled.setEnabled(false);
        assertThrows(AccessDeniedException.class, () -> AccessScope.of(disabled));
        assertThrows(AccessDeniedException.class, () -> AccessScope.of(null));
    }

    @Test void forbiddenAndMissingDocumentsAreDenied() {
        when(docs.findById("b")).thenReturn(Optional.of(doc("b", "B", false)));
        assertThrows(AccessDeniedException.class, () -> permissions.requireDocument(user("default", "A"), "b"));
        assertThrows(AccessDeniedException.class, () -> permissions.requireDocument(user("admin", "A"), "missing"));
        assertEquals("b", permissions.requireDocument(user("admin", null), "b").getFileId());
    }

    @Test void inconsistentParentDoesNotBroadenDocumentAccess() {
        Document d = doc("mixed", "A", false);
        d.setItem(Item.builder().itemId("i").deptName("B").isPublic(false).build());
        assertFalse(permissions.canRead(AccessScope.of(user("default", "A")), d));
        assertFalse(permissions.canRead(AccessScope.of(user("default", "B")), d));
        assertTrue(permissions.canRead(AccessScope.of(user("admin", null)), d));
    }

    @Test void staleIndexCannotLeakContentAndPathsComeFromSql() {
        Document a = doc("a", "A", false); a.setMinioPath("A/correct.pdf");
        when(docs.findAllById(any())).thenReturn(List.of(a, doc("b", "B", false), doc("p", "public", true)));
        List<ElasticsearchService.DocIndex> hits = List.of(
                ElasticsearchService.DocIndex.builder().docId("a").minioPath("B/wrong.pdf").build(),
                ElasticsearchService.DocIndex.builder().docId("b").isPublic(true).content("B secret").build(),
                ElasticsearchService.DocIndex.builder().docId("p").build(),
                ElasticsearchService.DocIndex.builder().docId("deleted").build());
        var filtered = permissions.filterSearchResults(user("default", "A"), hits);
        assertEquals(List.of("a", "p"), filtered.stream().map(ElasticsearchService.DocIndex::getDocId).toList());
        assertEquals("A/correct.pdf", filtered.get(0).getMinioPath());
        assertEquals("a.pdf", filtered.get(0).getFileName());
    }
}
