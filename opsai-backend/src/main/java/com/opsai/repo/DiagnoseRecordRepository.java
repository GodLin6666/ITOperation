package com.opsai.repo;

import com.opsai.entity.DiagnoseRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 诊断记录仓库 */
public interface DiagnoseRecordRepository extends JpaRepository<DiagnoseRecord, Long> {

    List<DiagnoseRecord> findByTicketIdOrderByCreateTimeDesc(Long ticketId);

    Optional<DiagnoseRecord> findFirstByTicketIdOrderByCreateTimeDesc(Long ticketId);

    long countByAdopted(Integer adopted);
}
