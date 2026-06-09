package com.knowledge.repository;

import com.knowledge.entity.Opinion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OpinionRepository extends JpaRepository<Opinion, Long> {
    List<Opinion> findByItemItemId(String itemId);
}
