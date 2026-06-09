package com.knowledge.repository;

import com.knowledge.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ItemRepository extends JpaRepository<Item, String> {

    @Query("SELECT i FROM Item i WHERE i.isPublic = true OR i.deptName = :deptName")
    List<Item> findAccessibleByDept(String deptName);

    List<Item> findByCategoryNo(String categoryNo);
}
