package com.govid.screening.repository;

import com.govid.screening.domain.FaceEnrolment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** Registry of reference face embeddings for known travellers. */
public interface FaceEnrolmentRepository extends MongoRepository<FaceEnrolment, String> {

    Page<FaceEnrolment> findAllByOrderByEnrolledAtDesc(Pageable pageable);

    List<FaceEnrolment> findByActiveTrue();

    List<FaceEnrolment> findBySubjectIdAndActiveTrue(String subjectId);

    List<FaceEnrolment> findByDocumentNumberKeyAndActiveTrue(String documentNumberKey);

    long countByActiveTrue();
}
