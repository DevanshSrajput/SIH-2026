package com.govid.screening.api.dto;

import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ScreeningCase;
import com.govid.screening.domain.Verdict;

import java.time.Instant;

/** Row shown in the case list. Deliberately small: the list must stay fast under load. */
public record CaseSummary(
        String id,
        String caseReference,
        DocumentType documentType,
        ScreeningCase.Status status,
        String fullName,
        String nationality,
        String documentNumber,
        Integer riskScore,
        String riskBand,
        Verdict verdict,
        Verdict officerDecision,
        int flagCount,
        String checkpointId,
        long processingMillis,
        Instant createdAt) {

    public static CaseSummary from(ScreeningCase source) {
        return new CaseSummary(
                source.getId(),
                source.getCaseReference(),
                source.getDocumentType(),
                source.getStatus(),
                source.getExtracted() == null ? null : source.getExtracted().fullName(),
                source.getExtracted() == null ? null : source.getExtracted().getNationality(),
                source.getExtracted() == null ? null : source.getExtracted().getDocumentNumber(),
                source.getRisk() == null ? null : source.getRisk().score(),
                source.getRisk() == null ? null : source.getRisk().band(),
                source.getRisk() == null ? null : source.getRisk().verdict(),
                source.getOfficerDecision(),
                source.getRisk() == null ? 0 : source.getRisk().flags().size(),
                source.getCheckpointId(),
                source.getProcessingMillis(),
                source.getCreatedAt());
    }
}
