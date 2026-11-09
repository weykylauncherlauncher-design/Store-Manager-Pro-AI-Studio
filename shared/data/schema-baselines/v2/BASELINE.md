# Schema v2 Current Baseline

This directory preserves an independent reference copy of the active Schema v2
before any Schema v3 migration work. The files were copied byte-for-byte from
the current v2 sources and generated SQLDelight snapshot.

## Schema identity

- Schema/user version: `2`
- Python SQLite runtime used for baseline verification: `3.37.2`
- Desktop SQLite JDBC dependency documented by the project: `org.xerial:sqlite-jdbc:3.53.4.0`
- Tables: `56`
- Explicit indexes: `108`
- Triggers: `186`
- Schema fingerprint using `DatabaseDriverFactory`'s normalized-schema SHA-256 algorithm:
  `562c9e435dce9efd31485831a2d2d84045ad80bd16b0bf82d858cccf96c022de`

The fingerprint was calculated from `2.db` plus the archived
`canonical_triggers.sql`. The SQLDelight snapshot itself has `user_version = 0`
and does not store the runtime trigger set; runtime initialization applies the
canonical triggers and sets `user_version = 2`.

## SHA-256

| File | SHA-256 |
|---|---|
| `StoreDatabase.sq` | `34108e389e2e34ac80439a9834cdcb69b56a2a9a1bc5b23846f8e65f319c124c` |
| `canonical_schema.sql` | `75ebe10b22a02c047fa87a6488dcddaef49b2cd67b5921bcefb5c4e94cc73b03` |
| `canonical_triggers.sql` | `5a4685094e59a854b1044674fd69e414db47d866c40131645581fae989d88b2a` |
| `seed_data.sql` | `77045da8be349a2d9d286a7b12c3c016cbef9e38ddfcc97c66087696e26eb57a` |
| `verify_canonical_schema.py` | `ba94e7782805783a4e5e69e36f36d3701de49bafd8e796ee5dc73b97ed10b89a` |
| `2.db` (unchanged SQLDelight snapshot) | `c21790b74af5b43adee5c01a436a9f1b546c6345bd42f586418f9d6519a48c4d` |

## Verification

- The archived verifier is intended to run as:
  `python3 shared/data/schema-baselines/v2/verify_canonical_schema.py`.
- The active v2 source inputs and the archived v2 copies must remain byte-for-byte identical.
- Baseline v1 remains historical and is not modified by this baseline.
- Schema v3 is not active and no migration is executed by this baseline creation.
