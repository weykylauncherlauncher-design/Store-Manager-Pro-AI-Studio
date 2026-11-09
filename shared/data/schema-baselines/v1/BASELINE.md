# Schema v1 Historical Baseline

This directory preserves the v1 source inputs for future validation and migration.
The files were copied byte-for-byte from the current v1 sources after the current
canonical verifier passed. The generated SQLDelight snapshot remains at
`shared/data/schemas/1.db` and was not modified.

## Schema identity

- Schema/user version: `1`
- SQLite runtime used by `verify_canonical_schema.py`: `3.37.2`
- Desktop SQLite JDBC dependency: `org.xerial:sqlite-jdbc:3.53.4.0`
- Tables: `55`
- Explicit indexes: `107`
- Triggers: `186`
- Fingerprint using `DatabaseDriverFactory`'s normalized-schema SHA-256 algorithm:
  `530e443c4a198a7dc0705b502b8b8fc49b0fa71dc602875f58497de9e7fd08d4`

The fingerprint was calculated from the unchanged `schemas/1.db` structure plus
`canonical_triggers.sql`, and independently from `canonical_schema.sql`. Both
produced the same fingerprint. `schemas/1.db` itself is a SQLDelight structural
snapshot: it has `user_version = 0`, 55 tables, 107 explicit indexes, and no
triggers. Runtime initialization adds the canonical triggers and sets
`user_version = 1`.

## SHA-256

Hashes below identify the original files and their byte-for-byte archived
copies. The snapshot hash identifies the unchanged file at
`shared/data/schemas/1.db`.

| File | SHA-256 |
|---|---|
| `StoreDatabase.sq` | `cecfcad98de754f8e65fe67430224179c903496c8aae7fbcbcd1645417935aba` |
| `canonical_schema.sql` | `0be4245cea446fa6479cbd8a187037eeb395e4d1bc6f2b5fd3782be3da9093c6` |
| `canonical_triggers.sql` | `5a4685094e59a854b1044674fd69e414db47d866c40131645581fae989d88b2a` |
| `seed_data.sql` | `465de2300f00c8cf1dbd0d3634487dbea2dc23c2775bf644fd85c4e46d4ed757` |
| `verify_canonical_schema.py` | `bfc3e36b8cd5cb913d046263e7a1d6c8def378ee6aff9ee204c3bf87fd86b765` |
| `shared/data/schemas/1.db` (unchanged) | `e54e58a85fbb2d3c0bd03bb0cd38f13100c60572dd15a35f83095f5c21952279` |

## Verification

- Current `python3 verify_canonical_schema.py`: `59 PASS / 0 FAIL`.
- Archived `python3 shared/data/schema-baselines/v1/verify_canonical_schema.py`:
  `59 PASS / 0 FAIL`.
- Both verifier runs reported 55 tables, 107 explicit indexes, and 186 triggers.
- The archived source-file hashes match the originals.
