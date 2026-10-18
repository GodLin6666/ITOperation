package com.opsai.repo;

import com.opsai.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 知识条目仓库，JPA 自动实现增删改查 */
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    List<KbDocument> findByStatusOrderByUpdateTimeDesc(Integer status);

    List<KbDocument> findAllByOrderByUpdateTimeDesc();

    List<KbDocument> findByDeviceTypeAndFaultCategoryOrderByUpdateTimeDesc(String deviceType, String faultCategory);

    long countByStatus(Integer status);
}
