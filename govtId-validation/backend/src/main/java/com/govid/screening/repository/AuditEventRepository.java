package com.govid.screening.repository;

import com.govid.screening.domain.AuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuditEventRepository extends MongoRepository<AuditEvent, String> {

    List<AuditEvent> findByCaseIdOrderByOccurredAtAsc(String caseId);
}
