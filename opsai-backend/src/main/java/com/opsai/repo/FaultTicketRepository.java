package com.opsai.repo;

import com.opsai.entity.FaultTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 故障单仓库 */
public interface FaultTicketRepository extends JpaRepository<FaultTicket, Long> {

    List<FaultTicket> findAllByOrderByCreateTimeDesc();
}
