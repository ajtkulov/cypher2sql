## ADDED Requirements

### Requirement: List CSV files in a data directory
The system SHALL list `*.csv` (and optionally `*.tsv`) files under a configured
data directory path such as `data/`.

#### Scenario: Inspect data directory
- **WHEN** inspect runs with `--data-dir data` containing `7000.inn.csv`
- **THEN** the report includes that filename

#### Scenario: Missing data directory
- **WHEN** the data directory does not exist
- **THEN** inspect returns a clear error

### Requirement: Report delimiter, headers, and row count
For each file, inspect SHALL detect or accept a delimiter, report header column
names, and report the number of data rows.

#### Scenario: Tab-separated result export
- **WHEN** a file uses tab separators and a header row like `p.person_hash`
- **THEN** inspect reports delimiter as tab (or equivalent) and lists those
  header names with a positive row count

### Requirement: Sample rows
Inspect SHALL include a small number of sample data rows (configurable, with a
sane default) for each file to help authors write mappings.

#### Scenario: Default samples
- **WHEN** inspect runs without an explicit sample size
- **THEN** each file summary includes at least one sample row when the file is
  non-empty
