# Validation Profiles

The `ili_validate` table function accepts a `profile` parameter that controls which validation checks are performed. Three profiles are available.

## Profiles

### `FULL` (Default)

All validation checks are performed:

| Check | Enabled |
|---|---|
| Constraint validation | Yes |
| AREA validation | Yes |
| Multiplicity validation | Yes |
| All objects accessible | Yes |

Use this for comprehensive data quality checks. This is the default if no `profile` is specified.

### `STRUCTURAL`

Structural and multiplicity checks without geometry AREA validation:

| Check | Enabled |
|---|---|
| Constraint validation | Yes |
| AREA validation | No |
| Multiplicity validation | Yes |
| All objects accessible | Yes |

Use this when geometry topology is not relevant.

### `FAST`

Minimal checks for quick validation:

| Check | Enabled |
|---|---|
| Constraint validation | No |
| AREA validation | No |
| Multiplicity validation | No |
| All objects accessible | No |

Use this for a quick structural scan. This is the fastest profile but catches the fewest issues.

## Usage

```sql
-- Full validation (default)
SELECT * FROM ili_validate('/data/myfile.xtf');

-- Explicit full validation
SELECT * FROM ili_validate('/data/myfile.xtf', profile := 'FULL');

-- Structural validation (no AREA checks)
SELECT * FROM ili_validate('/data/myfile.xtf', profile := 'STRUCTURAL');

-- Fast structural scan
SELECT * FROM ili_validate('/data/myfile.xtf', profile := 'FAST');
```

Combine with `max_messages` to limit output:

```sql
-- First 50 messages only, fast profile
SELECT * FROM ili_validate('/data/myfile.xtf',
    profile := 'FAST',
    max_messages := 50);
```

## Result Columns

All profiles produce the same output columns:

| Column | Type | Description |
|---|---|---|
| `severity` | VARCHAR | `ERROR`, `WARNING`, or `INFO` |
| `code` | VARCHAR | Message code |
| `message` | VARCHAR | Human-readable message |
| `filename` | VARCHAR | Source XTF file |
| `line` | INTEGER | Line number in XTF |
| `column` | INTEGER | Column number in XTF |
| `xtf_tid` | VARCHAR | Transfer ID of affected object |
| `xtf_bid` | VARCHAR | Basket ID |
| `model` | VARCHAR | INTERLIS model name |
| `topic` | VARCHAR | INTERLIS topic name |
| `class_name` | VARCHAR | INTERLIS class name |
| `attribute_name` | VARCHAR | Affected attribute name |
| `raw` | VARCHAR | Raw message text |

## Profile Selection Guide

| Scenario | Recommended Profile |
|---|---|
| Production data quality check | `FULL` |
| Fast pre-check before full validation | `FAST` |
| Geometry-less data | `STRUCTURAL` |
| Quick structural integrity check | `FAST` |
| Comprehensive acceptance test | `FULL` |
