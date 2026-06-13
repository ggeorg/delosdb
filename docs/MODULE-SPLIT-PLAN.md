# Module Split Plan

Status: completed historical record.

The original module split plan has been executed. DelosDB now builds as a
Gradle multi-project tree with explicit runtime artifact owners:

- `delosdb-commons`
- `delosdb-spi`
- `delosdb-engine`
- `delosdb-client`
- `delosdb-server`
- `delosdb-tools`
- `delosdb-runner`
- `delosdb-optionaltools`
- `delosdb-osgi-stub`
- `delosdb-storeless`

Do not use this document as a future plan. Current roadmap and next work live in
`docs/ROADMAP.md`.
