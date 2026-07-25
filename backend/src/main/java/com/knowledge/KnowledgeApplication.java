package com.knowledge;

import com.knowledge.service.ElasticsearchService;
import com.knowledge.service.ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
@EnableCaching
@RequiredArgsConstructor
public class KnowledgeApplication {

    private final ElasticsearchService esService;
    private final ImportService importService;

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }

    /** 启动时确保 ES 索引存在，并检测孤儿导入任务 */
    @Bean
    public ApplicationRunner onStartup() {
        return args -> {
            try {
                esService.ensureIndex();
                log.info("ES 索引初始化完成");
            } catch (Exception e) {
                log.error("ES 索引初始化失败", e);
            }
            importService.markOrphanedTasksAsFailed();
        };
    }
}
