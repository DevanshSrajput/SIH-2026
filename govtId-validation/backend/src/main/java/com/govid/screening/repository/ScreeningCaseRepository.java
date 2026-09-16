package com.govid.screening.repository;

import com.govid.screening.domain.ScreeningCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScreeningCaseRepository extends MongoRepository<ScreeningCase, String> {

    Optional<ScreeningCase> findByCaseReference(String caseReference);

    Page<ScreeningCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Prior presentations of the same physical document. */
    List<ScreeningCase> findByDocumentNumberKey(String documentNumberKey);

    /** Prior presentations by the same person, whatever document they used. */
    List<ScreeningCase> findByIdentityKey(String identityKey);

    long countByCreatedAtAfter(Instant since);

    List<ScreeningCase> findByCreatedAtAfter(Instant since);
}
