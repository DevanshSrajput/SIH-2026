"""A file-backed registry of enrolled faces, for 1:N identification.

This is the service's standalone store, so a face-verification node deployed on
its own at an edge checkpoint can still identify a repeat traveller with no
database behind it. In the full platform the Java backend keeps the authoritative
registry in MongoDB and calls ``/embed``; this store is what makes the Python
service useful without it.

Embeddings are stored L2-normalised, so identification is a dot product.
"""

import json
import logging
import threading
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import numpy as np

from config import settings

logger = logging.getLogger(__name__)


@dataclass
class Enrolment:
    """One enrolled traveller. A subject may hold several embeddings."""

    enrolment_id: str
    subject_id: str
    display_name: Optional[str]
    document_number: Optional[str]
    nationality: Optional[str]
    embedding: np.ndarray
    quality_score: float
    enrolled_at: str
    enrolled_by: Optional[str] = None
    notes: Optional[str] = None
    metadata: dict = field(default_factory=dict)

    def to_record(self) -> dict:
        return {
            "enrolmentId": self.enrolment_id,
            "subjectId": self.subject_id,
            "displayName": self.display_name,
            "documentNumber": self.document_number,
            "nationality": self.nationality,
            "embedding": [round(float(v), 6) for v in self.embedding],
            "qualityScore": self.quality_score,
            "enrolledAt": self.enrolled_at,
            "enrolledBy": self.enrolled_by,
            "notes": self.notes,
            "metadata": self.metadata,
        }

    def to_summary(self) -> dict:
        """The record without the embedding - what an API returns to a console."""
        record = self.to_record()
        record.pop("embedding")
        return record

    @staticmethod
    def from_record(record: dict) -> "Enrolment":
        return Enrolment(
            enrolment_id=record["enrolmentId"],
            subject_id=record["subjectId"],
            display_name=record.get("displayName"),
            document_number=record.get("documentNumber"),
            nationality=record.get("nationality"),
            embedding=np.asarray(record["embedding"], dtype=np.float64),
            quality_score=float(record.get("qualityScore", 0.0)),
            enrolled_at=record.get("enrolledAt", ""),
            enrolled_by=record.get("enrolledBy"),
            notes=record.get("notes"),
            metadata=record.get("metadata", {}),
        )


@dataclass
class Candidate:
    """One scored candidate from an identification run."""

    enrolment: Enrolment
    similarity: float

    def to_dict(self) -> dict:
        return {
            "subjectId": self.enrolment.subject_id,
            "enrolmentId": self.enrolment.enrolment_id,
            "displayName": self.enrolment.display_name,
            "documentNumber": self.enrolment.document_number,
            "nationality": self.enrolment.nationality,
            "similarity": round(self.similarity, 4),
        }


class EnrolmentStore:
    """Thread-safe JSON-file registry of enrolled face embeddings."""

    def __init__(self, path: Optional[Path] = None):
        self._path = Path(path or settings.ENROLMENT_DB)
        self._lock = threading.RLock()
        self._enrolments: dict[str, Enrolment] = {}
        self._loaded = False

    # -- persistence ------------------------------------------------------

    def _ensure_loaded(self):
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            if self._path.exists():
                try:
                    records = json.loads(self._path.read_text(encoding="utf-8"))
                    for record in records:
                        enrolment = Enrolment.from_record(record)
                        self._enrolments[enrolment.enrolment_id] = enrolment
                    logger.info(
                        "Loaded %d face enrolment(s) from %s", len(self._enrolments), self._path
                    )
                except (OSError, ValueError, KeyError) as e:
                    # A corrupt store must not take the service down, but it must
                    # be loud - silently identifying nobody looks identical to
                    # correctly identifying nobody.
                    logger.error("Could not read enrolment store %s: %s", self._path, e)
            self._loaded = True

    def _flush(self):
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = [e.to_record() for e in self._enrolments.values()]
        temporary = self._path.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        temporary.replace(self._path)

    # -- operations -------------------------------------------------------

    def enrol(
        self,
        *,
        subject_id: str,
        embedding: np.ndarray,
        quality_score: float,
        display_name: Optional[str] = None,
        document_number: Optional[str] = None,
        nationality: Optional[str] = None,
        enrolled_by: Optional[str] = None,
        notes: Optional[str] = None,
        metadata: Optional[dict] = None,
    ) -> Enrolment:
        """Register one embedding against a subject."""
        self._ensure_loaded()

        norm = float(np.linalg.norm(embedding))
        normalised = embedding / norm if norm > 0 else embedding

        enrolment = Enrolment(
            enrolment_id=uuid.uuid4().hex,
            subject_id=subject_id,
            display_name=display_name,
            document_number=document_number,
            nationality=nationality,
            embedding=np.asarray(normalised, dtype=np.float64),
            quality_score=quality_score,
            enrolled_at=datetime.now(timezone.utc).isoformat(),
            enrolled_by=enrolled_by,
            notes=notes,
            metadata=metadata or {},
        )

        with self._lock:
            self._enrolments[enrolment.enrolment_id] = enrolment
            self._flush()

        logger.info("Enrolled subject %s as %s", subject_id, enrolment.enrolment_id)
        return enrolment

    def identify(self, embedding: np.ndarray, *, limit: int = 5) -> list[Candidate]:
        """Score a probe embedding against every enrolment, best first."""
        self._ensure_loaded()

        norm = float(np.linalg.norm(embedding))
        if norm == 0:
            return []
        probe = np.asarray(embedding, dtype=np.float64) / norm

        with self._lock:
            enrolments = list(self._enrolments.values())

        candidates = []
        for enrolment in enrolments:
            if enrolment.embedding.shape != probe.shape:
                continue
            similarity = float(np.dot(probe, enrolment.embedding))
            candidates.append(Candidate(enrolment=enrolment, similarity=similarity))

        candidates.sort(key=lambda c: c.similarity, reverse=True)
        return candidates[:limit]

    def get(self, enrolment_id: str) -> Optional[Enrolment]:
        self._ensure_loaded()
        with self._lock:
            return self._enrolments.get(enrolment_id)

    def list_subjects(self) -> list[dict]:
        """Every enrolment, without embeddings, newest first."""
        self._ensure_loaded()
        with self._lock:
            enrolments = list(self._enrolments.values())
        enrolments.sort(key=lambda e: e.enrolled_at, reverse=True)
        return [e.to_summary() for e in enrolments]

    def delete(self, enrolment_id: str) -> bool:
        self._ensure_loaded()
        with self._lock:
            if enrolment_id not in self._enrolments:
                return False
            del self._enrolments[enrolment_id]
            self._flush()
        logger.info("Deleted enrolment %s", enrolment_id)
        return True

    def delete_subject(self, subject_id: str) -> int:
        """Remove every enrolment for one subject. Returns how many were removed."""
        self._ensure_loaded()
        with self._lock:
            doomed = [
                key for key, e in self._enrolments.items() if e.subject_id == subject_id
            ]
            for key in doomed:
                del self._enrolments[key]
            if doomed:
                self._flush()
        return len(doomed)

    @property
    def count(self) -> int:
        self._ensure_loaded()
        with self._lock:
            return len(self._enrolments)


def resolve_identification(candidates: list[Candidate]) -> dict:
    """Turn a ranked candidate list into an identification verdict.

    1:N is not 1:1 repeated. Every additional enrolled subject is another chance
    for a coincidental high score, so the bar is higher, and the best candidate
    must also beat the runner-up by a clear margin. A gallery that returns two
    near-equal candidates has not identified anyone - it has found a lookalike,
    and saying so is the only honest answer.
    """
    if not candidates:
        return {
            "identified": False,
            "decision": "NO_CANDIDATES",
            "reason": "No faces are enrolled to compare against.",
            "candidates": [],
        }

    best = candidates[0]
    runner_up = candidates[1] if len(candidates) > 1 else None
    margin = best.similarity - runner_up.similarity if runner_up else float("inf")

    payload = {
        "candidates": [c.to_dict() for c in candidates],
        "topSimilarity": round(best.similarity, 4),
        "margin": None if runner_up is None else round(margin, 4),
        "threshold": settings.IDENTIFY_THRESHOLD,
        "requiredMargin": settings.IDENTIFY_MARGIN,
    }

    if best.similarity < settings.IDENTIFY_THRESHOLD:
        payload.update(
            identified=False,
            decision="NO_MATCH",
            reason=(
                f"The closest enrolled face scores {best.similarity:.3f}, below the "
                f"{settings.IDENTIFY_THRESHOLD:.2f} identification threshold."
            ),
        )
        return payload

    if margin < settings.IDENTIFY_MARGIN:
        payload.update(
            identified=False,
            decision="AMBIGUOUS",
            reason=(
                f"Two enrolled faces score within {margin:.3f} of each other "
                f"({best.enrolment.subject_id} and {runner_up.subject_id}), which is closer "
                f"than the {settings.IDENTIFY_MARGIN:.2f} margin needed to name one of them."
            ),
        )
        return payload

    payload.update(
        identified=True,
        decision="IDENTIFIED",
        subjectId=best.enrolment.subject_id,
        displayName=best.enrolment.display_name,
        reason=(
            f"{best.enrolment.display_name or best.enrolment.subject_id} scores "
            f"{best.similarity:.3f}, clear of both the threshold and the runner-up."
        ),
    )
    return payload


enrolment_store = EnrolmentStore()
