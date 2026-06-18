package com.knowledge.service;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mozilla.universalchardet.UniversalDetector;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSV 数据导入服务
 * 负责解析 user.csv, item.csv, file_index.csv, item_with_opinions.csv
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private final DepartmentRepository deptRepo;
    private final UserRepository userRepo;
    private final ItemRepository itemRepo;
    private final DocumentRepository docRepo;
    private final OpinionRepository opinionRepo;
    private final PasswordEncoder passwordEncoder;
    /** Neo4j 图谱构建服务 —— 导入时同步创建图节点和关系 */
    private final GraphBuildService graphBuildService;

    /** 自动检测文件编码并读取为 CSV */
    public List<String[]> readCsv(Path filePath) throws IOException, CsvValidationException {
        byte[] raw = Files.readAllBytes(filePath);
        String encoding = detectEncoding(raw);
        log.debug("检测编码 {}: {}", filePath.getFileName(), encoding);

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(new ByteArrayInputStream(raw), Charset.forName(encoding)))) {
            List<String[]> rows = new ArrayList<>();
            String[] row;
            while ((row = reader.readNext()) != null) {
                rows.add(row);
            }
            return rows;
        }
    }

    /** 检测文件编码 */
    private String detectEncoding(byte[] raw) {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(raw, 0, raw.length);
        detector.dataEnd();
        String encoding = detector.getDetectedCharset();
        return encoding != null ? encoding : "UTF-8";
    }

    /**
     * 导入 user.csv
     * 格式: type,dept_name,username,password,role
     * type=dept 时创建部门, type=user 时创建用户
     */
    @Transactional
    public Map<String, Integer> importUserCsv(Path filePath) throws Exception {
        List<String[]> rows = readCsv(filePath);
        int deptCount = 0, userCount = 0;

        // 跳过表头 (第一行)
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 5) continue;

            String type = row[0].trim();
            String deptName = row[1].trim();
            String username = row[2].trim();
            String password = row[3].trim();
            String role = row[4].trim();

            if ("dept".equals(type)) {
                createDeptIfNotExist(deptName);
                deptCount++;
            } else if ("user".equals(type)) {
                createUserIfNotExist(deptName, username, password, role);
                userCount++;
            }
        }

        log.info("user.csv 导入完成: {} 个部门, {} 个用户", deptCount, userCount);
        return Map.of("departments", deptCount, "users", userCount);
    }

    private void createDeptIfNotExist(String name) {
        if (!deptRepo.existsByName(name)) {
            deptRepo.save(Department.builder().name(name).build());
        }
        // Neo4j MERGE 幂等，每次都调用确保图节点存在，
        // 避免"MySQL 已有但 Neo4j 缺失"的脏状态
        graphBuildService.createDepartment(name);
    }

    private void createUserIfNotExist(String deptName, String username,
                                       String password, String role) {
        if (!userRepo.existsByUsername(username)) {
            Department dept = deptRepo.findByName(deptName)
                    .orElseGet(() -> {
                        Department d = Department.builder().name(deptName).build();
                        deptRepo.save(d);
                        return d;
                    });

            userRepo.save(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(password))
                    .role(role != null && !role.isEmpty() ? role : "default")
                    .department(dept)
                    .build());

            log.info("创建用户: {} ({})", username, deptName);
        }
        // Neo4j 图节点每次导入都确保存在，不受 MySQL 已有记录影响
        graphBuildService.createDepartment(deptName);
        graphBuildService.createUser(username, role != null ? role : "default", deptName);
    }

    /**
     * 导入 item.csv — 事项元数据
     * 格式: 事项ID, 事项标题, 事项发起时间, 事项分类, 分类编号, 年度, 字号, 发文单位, 事项类型
     */
    @Transactional
    public List<Item> importItemCsv(Path filePath, String deptName, boolean isPublic,
                                     String batchId) throws Exception {
        List<String[]> rows = readCsv(filePath);
        List<Item> items = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 9) continue;

            String itemId = row[0].trim();
            Item item = itemRepo.findById(itemId).orElse(Item.builder().itemId(itemId).build());

            item.setTitle(row[1].trim());
            try { item.setCreatedAt(LocalDateTime.parse(row[2].trim(), fmt)); } catch (Exception ignored) {}
            item.setCategory(row[3].trim());
            item.setCategoryNo(row[4].trim());
            item.setYear(row[5].trim());
            item.setRefNo(row[6].trim());
            item.setIssuer(row[7].trim());
            item.setItemType(row[8].trim());
            item.setDeptName(deptName);
            item.setIsPublic(isPublic);
            item.setImportBatch(batchId);
            item.setImportTime(LocalDateTime.now());

            items.add(itemRepo.save(item));

            // 同步 Neo4j
            graphBuildService.createItem(itemId, row[1].trim(), row[3].trim(), deptName, isPublic);
            graphBuildService.linkDeptOwnsItem(deptName, itemId);
        }

        log.info("item.csv 导入完成: {} 条事项", items.size());
        return items;
    }

    /**
     * 导入 file_index.csv — 文件→事项映射
     * 格式: 文件ID, 文件名, 事项标题, 事项ID
     */
    @Transactional
    public List<Document> importFileIndexCsv(Path filePath, String deptName,
                                              boolean isPublic, String batchId) throws Exception {
        List<String[]> rows = readCsv(filePath);
        List<Document> docs = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 4) continue;

            String fileId = row[0].trim();
            String fileName = row[1].trim();
            String itemId = row[3].trim();

            Item item = itemRepo.findById(itemId).orElse(null);

            Document doc = docRepo.findById(fileId).orElse(
                    Document.builder().fileId(fileId).build());

            doc.setFileName(fileName);
            doc.setItem(item);
            doc.setStatus("expected"); // 等待实际文件匹配
            doc.setDeptName(deptName);
            doc.setIsPublic(isPublic);
            doc.setImportBatch(batchId);

            docs.add(docRepo.save(doc));

            // 同步 Neo4j
            graphBuildService.createDocument(fileId, fileName, "expected");
            if (item != null) {
                graphBuildService.linkItemContainsDoc(itemId, fileId);
            }
        }

        log.info("file_index.csv 导入完成: {} 条映射", docs.size());
        return docs;
    }

    /**
     * 导入 item_with_opinions.csv — 签阅记录
     * 格式: 事项ID, 事项标题, 签阅时间, 签阅人, 签阅意见
     */
    @Transactional
    public List<Opinion> importOpinionsCsv(Path filePath, String batchId) throws Exception {
        List<String[]> rows = readCsv(filePath);
        List<Opinion> opinions = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy/M/d H:mm");

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 5) continue;

            Item item = itemRepo.findById(row[0].trim()).orElse(null);

            Opinion opinion = Opinion.builder()
                    .item(item)
                    .signer(row[3].trim())
                    .content(row[4].trim())
                    .importTime(LocalDateTime.now())
                    .build();

            try { opinion.setSignTime(LocalDateTime.parse(row[2].trim(), fmt)); } catch (Exception ignored) {}

            opinions.add(opinionRepo.save(opinion));

            // 同步 Neo4j
            if (item != null) {
                graphBuildService.createOpinion(item.getItemId(),
                        row[3].trim(), row[4].trim(), opinion.getSignTime());
            }
        }

        log.info("item_with_opinions.csv 导入完成: {} 条签阅", opinions.size());
        return opinions;
    }
}
