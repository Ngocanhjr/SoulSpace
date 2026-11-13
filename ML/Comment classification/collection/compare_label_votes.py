"""Split matching annotation CSVs into unanimous and non-unanimous records."""

import argparse
import csv
import hashlib
from pathlib import Path


FIELDS = ['source', 'source_name', 'source_id', 'document_id', 'parent_id',
          'free_text', 'published_at', 'collected_at', 'url', 'context', 'label_id']
KEY = ['source', 'source_name', 'source_id']


def read(path, require_label=True, allow_needs_review=False):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream, strict=True)
        fields = reader.fieldnames or []
        if not set(FIELDS).issubset(fields) or len(fields) != len(set(fields)):
            raise ValueError(f'{path}: missing required columns or duplicate column names')
        rows = list(reader)
    index = {}
    for row in rows:
        if None in row or any(v is None for v in row.values()):
            raise ValueError(f'{path}: malformed record')
        key = tuple(row[k] for k in KEY)
        if not row['source_id'] or key in index:
            raise ValueError(f'{path}: empty or duplicate comment ID: {key}')
        needs_review = (allow_needs_review and row['label_id'] == ''
                        and row.get('annotation_status') == 'NEEDS_REVIEW')
        if require_label and row['label_id'] not in {'0', '1', '2'} and not needs_review:
            raise ValueError(f'{path}: invalid label for {key}')
        index[key] = row
    return fields, rows, index


def compare(inputs, output_dir, reference=None, allow_needs_review=False):
    if len(inputs) < 2 or len({n for n, _ in inputs}) != len(inputs):
        raise ValueError('Need at least two uniquely named annotation sources')
    loaded = [(name, path, *read(path, allow_needs_review=allow_needs_review))
              for name, path in inputs]
    _, rows, expected = read(reference, False) if reference else loaded[0][2:]
    if not rows:
        raise ValueError('Reference dataset is empty')
    for name, path, fields, _, index in loaded:
        if index.keys() != expected.keys():
            raise ValueError(f'{name}: comment IDs differ from reference '
                             f'(missing={len(expected.keys()-index.keys())}, '
                             f'extra={len(index.keys()-expected.keys())})')
        for key, row in expected.items():
            if any(row[k] != index[key][k] for k in FIELDS if k != 'label_id'):
                raise ValueError(f'{name}: content/metadata differs for {key}')
    # Preserve previous rounds from a supplied reference and namespace model extras.
    history = [k for k in rows[0] if k not in FIELDS and k not in
               {'data_row', 'vote_pattern', 'agreement_status'}] if reference else []
    extra = [(name, k) for name, _, fields, _, _ in loaded
             for k in fields if k not in FIELDS and k not in history
             and k not in {'data_row', 'vote_pattern', 'agreement_status'}]
    fields = FIELDS + ['data_row'] + history + [f'vote_{n}' for n, _ in inputs] + [
        'vote_pattern', 'agreement_status'] + [f'{n}__{k}' for n, k in extra]
    if len(fields) != len(set(fields)):
        raise ValueError('Column names collide; use different model names for this round')
    outputs = {'unanimous': [], 'disagreement': []}
    for number, row in enumerate(rows, 1):
        key = tuple(row[k] for k in KEY)
        votes = [index[key]['label_id'] for _, _, _, _, index in loaded]
        status = ('unanimous' if len(set(votes)) == 1 and votes[0] in {'0', '1', '2'}
                  else 'disagreement')
        result = {k: row[k] for k in FIELDS}
        result.update({k: row[k] for k in history})
        result.update(label_id=votes[0] if status == 'unanimous' else '',
                      data_row=row.get('data_row', str(number)),
                      vote_pattern='|'.join(votes), agreement_status=status)
        result.update({f'vote_{name}': vote for (name, _), vote in zip(inputs, votes)})
        for name, _, _, _, index in loaded:
            result.update({f'{name}__{k}': index[key][k] for n, k in extra if n == name})
        outputs[status].append(result)
    paths = [output_dir / f'comments.{status}.csv' for status in outputs]
    if any(p.exists() for p in paths):
        raise ValueError('Output exists; choose a new output directory')
    output_dir.mkdir(parents=True, exist_ok=True)
    for path, (status, records) in zip(paths, outputs.items()):
        with path.open('x', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(records)
        print(f'{path}: {len(records)} records')
    for name, path in inputs:
        print(f'{name}: SHA-256 {hashlib.sha256(path.read_bytes()).hexdigest()}')
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', nargs='+', required=True, metavar='MODEL=CSV')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--reference', type=Path,
                        help='Previous disagreement file; verifies complete coverage and preserves history')
    parser.add_argument('--allow-needs-review', action='store_true',
                        help='Allow blank labels explicitly marked annotation_status=NEEDS_REVIEW; '
                             'these records always remain in disagreement')
    args = parser.parse_args()
    try:
        inputs = []
        for value in args.inputs:
            name, path = value.split('=', 1)
            if not name or not all(c.isalnum() or c == '_' for c in name):
                raise ValueError('Model names must use letters, digits or underscores')
            inputs.append((name, Path(path)))
        compare(inputs, args.output_dir, args.reference, args.allow_needs_review)
    except (ValueError, OSError, csv.Error) as error:
        parser.exit(1, f'Error: {error}\n')


if __name__ == '__main__':
    main()
