package com.govid.screening.repository;

import com.govid.screening.domain.WatchlistEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WatchlistRepository extends MongoRepository<WatchlistEntry, String> {

    List<WatchlistEntry> findByDocumentNumberKeyAndActiveIsTrue(String documentNumberKey);

    List<WatchlistEntry> findByIdentityKeyAndActiveIsTrue(String identityKey);

    Page<WatchlistEntry> findAllByOrderByAddedAtDesc(Pageable pageable);
}
