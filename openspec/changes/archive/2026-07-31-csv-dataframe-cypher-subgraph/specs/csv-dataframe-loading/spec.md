## ADDED Requirements

### Requirement: Load CSV into Tablesaw table
The system SHALL load a CSV file with a header row into a Tablesaw `Table`
whose column names match the CSV headers.

#### Scenario: Successful CSV load
- **WHEN** a well-formed CSV path is provided to the loader
- **THEN** the result is a Tablesaw table with one column per header and one row
  per data line

#### Scenario: Missing CSV file
- **WHEN** the CSV path does not exist
- **THEN** the loader returns a clear error (no silent empty table)

### Requirement: No Spark dependency
CSV/DataFrame loading MUST NOT depend on Apache Spark, Spark SQL, or Spark
DataFrame/Dataset APIs.

#### Scenario: Build classpath
- **WHEN** the project is compiled with the new dataframe loading module
- **THEN** the dependency graph includes Tablesaw (and optionally Arrow) but
  does not include Spark artifacts

### Requirement: Optional Arrow interchange
The system SHALL support exporting a loaded Tablesaw table to Apache Arrow
and/or importing Arrow IPC into a Tablesaw table when Arrow support is enabled,
without requiring Arrow for the default CSV path.

#### Scenario: Tablesaw-only default path
- **WHEN** a caller loads CSV without requesting Arrow
- **THEN** loading succeeds using Tablesaw alone
