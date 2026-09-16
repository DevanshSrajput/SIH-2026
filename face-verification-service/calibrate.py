"""Measure this deployment's actual score distributions and check the thresholds.

A threshold copied from a paper is a guess about your cameras, your lighting and
your document scanners. This script replaces the guess with a measurement.

Point it at a folder of face images grouped by person - one subdirectory per
person - and it reports:

  * the genuine distribution  (two images of the same person)
  * the impostor distribution (two images of different people)
  * the false accept rate at the configured match threshold
  * the false reject rate at the configured match threshold
  * the threshold that would give zero false accepts on this data

Layout:

    samples/
      alice/  passport.jpg  live1.jpg  live2.jpg
      bob/    passport.jpg  live1.jpg
      ...

Usage:

    python calibrate.py samples/
    python calibrate.py samples/ --target-far 0.0001

The false accept rate is the number that matters at a checkpoint. If it is not
zero on your own data at the configured threshold, raise the threshold until it
is, and accept the extra referrals as the price.
"""

import argparse
import itertools
import logging
import sys
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

from config import settings
from face_detector import face_detector
from face_quality import assess_quality
from face_recognizer import face_recognizer

logging.basicConfig(level=logging.WARNING, format="%(levelname)s: %(message)s")

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}


def load_embeddings(root: Path, *, skip_poor_quality: bool) -> dict[str, list[tuple[str, np.ndarray]]]:
    """Embed every image under root, grouped by the subdirectory name."""
    by_person: dict[str, list[tuple[str, np.ndarray]]] = defaultdict(list)
    skipped = 0

    for person_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        for image_path in sorted(person_dir.iterdir()):
            if image_path.suffix.lower() not in IMAGE_SUFFIXES:
                continue

            image = cv2.imread(str(image_path))
            if image is None:
                print(f"  ! could not read {image_path}", file=sys.stderr)
                continue

            faces = face_detector.detect(image)
            if not faces:
                print(f"  ! no face detected in {image_path}", file=sys.stderr)
                continue

            source = "document" if "passport" in image_path.stem.lower() or "doc" in image_path.stem.lower() else "live"
            quality = assess_quality(image, faces[0], source=source)
            if skip_poor_quality and not quality.usable:
                print(
                    f"  - skipping {image_path} (quality {quality.score:.2f}: "
                    f"{quality.issues[0] if quality.issues else 'below gate'})",
                    file=sys.stderr,
                )
                skipped += 1
                continue

            embedding = face_recognizer.extract_embedding(image, faces[0])
            if embedding is None:
                print(f"  ! could not embed {image_path}", file=sys.stderr)
                continue

            by_person[person_dir.name].append(
                (image_path.name, face_recognizer.normalise(embedding.embedding))
            )

    if skipped:
        print(f"  ({skipped} image(s) skipped on quality)", file=sys.stderr)
    return by_person


def score_pairs(by_person: dict[str, list[tuple[str, np.ndarray]]]):
    """Return (genuine scores, impostor scores, worst impostor pairs)."""
    genuine: list[float] = []
    impostor: list[float] = []
    impostor_pairs: list[tuple[float, str, str]] = []

    for person, items in by_person.items():
        for (name_a, a), (name_b, b) in itertools.combinations(items, 2):
            genuine.append(float(np.dot(a, b)))

    for person_a, person_b in itertools.combinations(sorted(by_person), 2):
        for name_a, a in by_person[person_a]:
            for name_b, b in by_person[person_b]:
                score = float(np.dot(a, b))
                impostor.append(score)
                impostor_pairs.append(
                    (score, f"{person_a}/{name_a}", f"{person_b}/{name_b}")
                )

    impostor_pairs.sort(reverse=True)
    return genuine, impostor, impostor_pairs


def describe(label: str, scores: list[float]) -> None:
    if not scores:
        print(f"{label:<12} no pairs")
        return
    array = np.asarray(scores)
    print(
        f"{label:<12} n={len(array):<5} "
        f"min={array.min():.3f}  p5={np.percentile(array, 5):.3f}  "
        f"median={np.median(array):.3f}  p95={np.percentile(array, 95):.3f}  "
        f"max={array.max():.3f}  mean={array.mean():.3f}  sd={array.std():.3f}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("samples", type=Path, help="Folder with one subdirectory per person")
    parser.add_argument(
        "--target-far",
        type=float,
        default=0.0,
        help="Target false accept rate; the reported threshold achieves it (default 0)",
    )
    parser.add_argument(
        "--include-poor-quality",
        action="store_true",
        help="Include images that fail the quality gate (they normally never reach a comparison)",
    )
    args = parser.parse_args()

    if not args.samples.is_dir():
        print(f"Not a directory: {args.samples}", file=sys.stderr)
        return 2

    print(f"Reading samples from {args.samples}")
    by_person = load_embeddings(args.samples, skip_poor_quality=not args.include_poor_quality)

    usable = {p: items for p, items in by_person.items() if items}
    if len(usable) < 2:
        print(
            "Need at least two people with at least one usable image each.",
            file=sys.stderr,
        )
        return 2

    total = sum(len(v) for v in usable.values())
    print(f"Embedded {total} image(s) across {len(usable)} people\n")

    genuine, impostor, impostor_pairs = score_pairs(usable)

    print("Raw cosine similarity distributions")
    print("-" * 78)
    describe("genuine", genuine)
    describe("impostor", impostor)
    print()

    match_at = settings.MATCH_THRESHOLD
    mismatch_at = settings.MISMATCH_THRESHOLD

    genuine_arr = np.asarray(genuine) if genuine else np.asarray([])
    impostor_arr = np.asarray(impostor) if impostor else np.asarray([])

    print(f"At the configured thresholds (match >= {match_at:.2f}, mismatch < {mismatch_at:.2f})")
    print("-" * 78)

    if impostor_arr.size:
        false_accepts = int(np.sum(impostor_arr >= match_at))
        far = false_accepts / impostor_arr.size
        verdict = "PASS" if false_accepts == 0 else "FAIL"
        print(
            f"  false accepts      {false_accepts}/{impostor_arr.size} "
            f"(FAR {far:.4%})   [{verdict}]"
        )
        referred = int(np.sum((impostor_arr >= mismatch_at) & (impostor_arr < match_at)))
        print(
            f"  impostors referred {referred}/{impostor_arr.size} "
            f"({referred / impostor_arr.size:.2%}) - sent to an officer, not accepted"
        )

    if genuine_arr.size:
        false_rejects = int(np.sum(genuine_arr < mismatch_at))
        accepted = int(np.sum(genuine_arr >= match_at))
        print(
            f"  false rejects      {false_rejects}/{genuine_arr.size} "
            f"(FRR {false_rejects / genuine_arr.size:.2%})"
        )
        print(
            f"  genuine accepted   {accepted}/{genuine_arr.size} "
            f"({accepted / genuine_arr.size:.2%}) - the rest go to an officer"
        )
    print()

    if impostor_arr.size:
        print("Recommended thresholds from this data")
        print("-" * 78)
        if args.target_far <= 0:
            # Sit clearly above the highest impostor score seen.
            safe = float(impostor_arr.max()) + 0.05
            print(f"  highest impostor score observed : {impostor_arr.max():.3f}")
            print(f"  match threshold for zero FAR    : {safe:.3f}")
        else:
            quantile = 100.0 * (1.0 - args.target_far)
            safe = float(np.percentile(impostor_arr, quantile))
            print(f"  match threshold for FAR {args.target_far:.4%} : {safe:.3f}")

        if genuine_arr.size:
            retained = float(np.mean(genuine_arr >= safe))
            print(f"  genuine pairs still auto-accepted at that threshold: {retained:.2%}")
        if safe > match_at:
            print(
                f"\n  ACTION: the configured MATCH_THRESHOLD of {match_at:.2f} admits impostors "
                f"on this data.\n          Raise it to at least {safe:.2f} "
                f"(export MATCH_THRESHOLD={safe:.2f})."
            )
        else:
            print(
                f"\n  The configured MATCH_THRESHOLD of {match_at:.2f} is at or above the "
                f"safe value for this data."
            )
        print()

        print("Closest impostor pairs (the ones that would break first)")
        print("-" * 78)
        for score, a, b in impostor_pairs[:5]:
            marker = "  <-- ACCEPTED" if score >= match_at else ""
            print(f"  {score:.3f}  {a}  vs  {b}{marker}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
