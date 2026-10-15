package com.knowledge.service;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@org.springframework.test.context.ContextConfiguration(classes = JpaTestConfiguration.class)
@ActiveProfiles("test")
class PermissionRepositoryTest {
    @Autowired ItemRepository items;
    @Autowired DocumentRepository docs;
    @Autowired DepartmentRepository departments;
    @Autowired UserRepository users;
    @Autowired OpinionRepository opinions;

    @Test void structuredQueriesRespectAdminDepartmentAndPublic() {
        items.saveAll(List.of(
                Item.builder().itemId("a").title("报告 A").deptName("A").isPublic(false).build(),
                Item.builder().itemId("b").title("报告 B").deptName("B").isPublic(false).build(),
                Item.builder().itemId("p").title("报告 公共").deptName("public").isPublic(true).build()));
        assertEquals(Set.of("a", "p"), ids(items.searchByTitleKeyword("报告", false, "A", PageRequest.of(0, 10))));
        assertEquals(Set.of("p"), ids(items.searchByTitleKeyword("报告", false, null, PageRequest.of(0, 10))));
        assertEquals(Set.of("a", "b", "p"), ids(items.searchByTitleKeyword("报告", true, null, PageRequest.of(0, 10))));
    }

    @Test void importCandidateQueryIsScoped() {
        docs.saveAll(List.of(
                Document.builder().fileId("a").fileName("same.pdf").deptName("A").isPublic(false).build(),
                Document.builder().fileId("b").fileName("same.pdf").deptName("B").isPublic(false).build()));
        assertEquals(List.of("a"), docs.findByDeptNameAndIsPublic("A", false).stream().map(Document::getFileId).toList());
    }

    @Test void graphDocumentIdsEnforceBothDocumentAndParentPermissions() {
        Item foreign = items.save(Item.builder().itemId("parent").title("B").deptName("B").isPublic(false).build());
        docs.saveAll(List.of(
                Document.builder().fileId("a").fileName("a.pdf").deptName("A").isPublic(false).build(),
                Document.builder().fileId("p").fileName("p.pdf").deptName("public").isPublic(true).build(),
                Document.builder().fileId("conflict").fileName("x.pdf").deptName("A").isPublic(false).item(foreign).build()));
        assertEquals(Set.of("a", "p"), new HashSet<>(docs.findAccessibleIds(false, "A")));
        assertEquals(List.of("p"), docs.findAccessibleIds(false, null));
        assertEquals(Set.of("a", "p", "conflict"), new HashSet<>(docs.findAccessibleIds(true, null)));
    }

    @Test void csvCannotReassignExistingItemOrFileToAnotherDepartment(@TempDir Path temp) throws Exception {
        var graph = mock(GraphBuildService.class);
        var csv = new CsvImportService(departments, users, items, docs, opinions, mock(PasswordEncoder.class), graph);
        items.save(Item.builder().itemId("i").title("B original").deptName("B").isPublic(false).build());
        docs.save(Document.builder().fileId("f").fileName("B.pdf").deptName("B").isPublic(false).build());
        Path itemCsv = temp.resolve("item.csv");
        Files.writeString(itemCsv, "id,title,time,category,no,year,ref,issuer,type\ni,new,2026-01-01 00:00:00,c,n,2026,r,x,t\n");
        assertThrows(IllegalArgumentException.class, () -> csv.importItemCsv(itemCsv, "A", false, "batch"));
        Path fileCsv = temp.resolve("file.csv");
        Files.writeString(fileCsv, "id,name,title,item\nf,new.pdf,t,missing\n");
        assertThrows(IllegalArgumentException.class, () -> csv.importFileIndexCsv(fileCsv, "A", false, "batch"));
        assertEquals("B original", items.findById("i").orElseThrow().getTitle());
        assertEquals("B.pdf", docs.findById("f").orElseThrow().getFileName());
        verifyNoInteractions(graph);
    }

    @Test void csvCannotAttachDocumentOrOpinionToForeignItem(@TempDir Path temp) throws Exception {
        var graph = mock(GraphBuildService.class);
        var csv = new CsvImportService(departments, users, items, docs, opinions, mock(PasswordEncoder.class), graph);
        items.save(Item.builder().itemId("b").title("B").deptName("B").isPublic(false).build());
        Path file = temp.resolve("file.csv");
        Files.writeString(file, "id,name,title,item\nf,new.pdf,t,b\n");
        assertThrows(IllegalArgumentException.class, () -> csv.importFileIndexCsv(file, "A", false, "batch"));
        Path opinion = temp.resolve("opinion.csv");
        Files.writeString(opinion, "id,title,time,signer,content\nb,title,2026/1/1 10:00,user,opinion\n");
        assertThrows(IllegalArgumentException.class, () -> csv.importOpinionsCsv(opinion, "A", false, "batch"));
        assertEquals(0, docs.count());
        assertEquals(0, opinions.count());
        verifyNoInteractions(graph);
    }

    private Set<String> ids(List<Item> list) {
        return new HashSet<>(list.stream().map(Item::getItemId).toList());
    }
}
