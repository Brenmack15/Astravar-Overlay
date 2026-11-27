# Astravar 4.1 acceptance cross-reference

The standalone CSV maps 793 uniquely identified engineering rows. The 503 primary backlog rows include all 344 original checklist paragraphs. Each original checkbox has one source-line identifier. The remaining 159 rows preserve acceptance, historical and decision paragraphs.

| Register | Rows |
| --- | ---: |
| [Release directive](../requirements.csv) | 175 |
| [Primary backlog](../requirements-backlog.csv) | 503 |
| [Mechanical fixtures](../requirements-fixtures.csv) | 52 |
| [Authorized DM capabilities](../capabilities.csv) | 63 |

Use the row ID to find the full acceptance condition, implementation paths and remaining work in its register. Each evidence ID resolves to one of 206 entries in the [observation register](../requirements-candidate-evidence.csv). The [two-client protocol report](../protocol-parity.json) records the separately verified API operations.

The standalone CSV contains engineering identifiers, status and references. A capability, a directive and its individual acceptance tests can refer to the same observed result.

**Primary source rows awaiting some or all acceptance**

- PARTIAL · BL26-0072, BL26-0091, BL26-0096, BL26-0105, BL26-0106, BL26-0107, BL26-0192, BL26-0254, BL26-0360, BL26-0419, BL26-0945
- BLOCKED_EXTERNAL · None
- PROPOSED · BL26-0299, BL26-0626, BL26-0878
- OPEN_INTAKE · BL26-0931

| Status | Rows |
| --- | ---: |
| HISTORICAL | 51 |
| INDEPENDENTLY_VERIFIED | 572 |
| OPEN_INTAKE | 1 |
| OUTSIDE | 11 |
| OWNER_EXCEPTION | 4 |
| PARTIAL | 32 |
| PROPOSED | 4 |
| REFERENCE | 25 |
| SUPERSEDED | 31 |
| VERIFIED | 62 |

Standalone CSV SHA-256 `66adc5e4fe8224c0cf685b300b1f7f1188263eb3f7c95cc00cb208442969581a`.
