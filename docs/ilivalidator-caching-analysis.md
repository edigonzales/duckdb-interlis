# ilivalidator Internal Caching Analysis

> Phase 5.4 – Investigation of ilivalidator's internal caching mechanisms.
> Generated 2026-06-07 from bytecode analysis of ilivalidator 1.15.0 and iox-ili 1.24.4.

## Architecture Overview

ilivalidator has a two-layer Validator architecture:

```
org.interlis2.validator.Validator  (outer – API surface)
    └── ch.interlis.iox_j.validator.Validator  (inner – actual validation engine)
```

## 1. Model Compilation

**Finding:** ilivalidator does **NOT** cache TransferDescription across `validate()` calls.

- `org.interlis2.validator.Validator.validate()` calls `Validator.compileIli()` every invocation
- `compileIli()` is a static method that creates a fresh `IliManager`, sets repositories, and runs the ili2c compiler
- The compiled `TransferDescription` is stored in the instance field `td` and accessible via `getModel()`
- There is no static cache, no reuse of previously compiled models

**Implication:** Our own `ModelCache` provides a genuine performance improvement by caching `TransferDescription` across calls.

## 2. Validation Phases

Bytecode analysis reveals a multi-phase validation flow:

### Phase A – Model Compilation
- `compileIli()` (bytecode offset ~3787)
- Creates `TransferDescription` from all .ili files in model directories
- Stored in `td` instance field

### Phase B – Reader Pipeline Setup
1. `PipelinePool` created (offset 4850) – fresh instance per call
2. `ch.interlis.iox_j.validator.Validator` instantiated with the `td` (offset 4876)
3. `IoxReader` created via `createReader()` (offset 4952)
4. XTF file opened and parsed

### Phase C – Object Reading
- `reader.read()` loop (offset 5105) iterates all `IoxEvent`s
- Events: `StartBasketEvent`, `ObjectEvent`, `EndBasketEvent`
- Objects loaded into `ObjectPool` when `allObjectsAccessible=true`

### Phase D – Type & Multiplicity Validation
- Per-object checks: type correctness, attribute types, multiplicities
- Does not require all objects simultaneously

### Phase E – Constraint Validation (if enabled)
- Global constraints, set constraints, plausibility constraints
- **Requires all objects in memory** (`allObjectsAccessible=true`)
- Uses `ObjectPool` and `LinkPool` for cross-object references

### Phase F – AREA Validation (if enabled)
- Geometry area/topology checks
- May require full spatial context

## 3. Object Storage & Pools

### ObjectPool (`ch.interlis.iox_j.validator.ObjectPool`)
- Primary object store when `SETTING_ALL_OBJECTS_ACCESSIBLE=TRUE`
- Stores all `IomObject`s extracted from XTF
- Enables forward/backward reference resolution
- **Per-validation-run, not cacheable** – contains actual XTF data

### LinkPool (`ch.interlis.iox_j.validator.LinkPool`)
- Tracks cross-object references (e.g., REFERENCE TO, association roles)
- Resolves forward references (object A references object B that appears later in XTF)
- **Per-validation-run**

### PipelinePool (`ch.interlis.iox_j.PipelinePool`)
- `HashMap<Object, Map<String, Object>> elements` – intermediate values per pipeline stage
- Used for passing data between validation pipeline components
- Methods: `addDataObject()`, `getDataObject()`, `setIntermediateValue()`, `getIntermediateValue()`
- **Per-validation-run, freshly created**

## 4. All Objects Accessible (SETTING_ALL_OBJECTS_ACCESSIBLE)

When enabled (which is our default):
- All XTF objects are loaded into `ObjectPool` before validation begins
- Enables global constraints, set constraints, and basket uniqueness checks
- Memory proportional to XTF file size (all objects in memory)
- **Cannot be streamed** – this is the fundamental tradeoff

When disabled:
- Objects can be validated as they are read (streaming)
- Cannot validate global constraints, set constraints, or basket uniqueness
- Lower memory footprint

## 5. Constraint Types

From the `ch.interlis.iox_j.validator.Validator` fields:

| Internal field | INTERLIS concept | Requires all objects? |
|---|---|---|
| `additionalConstraints` | Global constraints (`CONSTRAINT`) | Yes |
| `plausibilityConstraints` | Plausibility constraints | Yes |
| `setConstraints` | Set constraints (`SET CONSTRAINT`) | Yes |
| Per-object type checks | Type validation | No |
| Multiplicity checks | Multiplicity validation | No |

## 6. Basket Handling

- `mandatoryBaskets`, `optionalBaskets`, `bannedBaskets` – filter which baskets to validate
- `uniquenessOfBid` – ensures basket IDs are unique
- `stableBids` – tracks basket ID stability
- `seenTopics` – tracks which topics have been observed

## 7. Tag-to-Class Mapping

- `tag2class: HashMap<String, Object>` maps XTF object tags to INTERLIS class definitions
- Built during model loading, used for fast class dispatch during object reading
- **Built per validation run from the TransferDescription**

## 8. Single-Pass Mode

- `singlePass` flag (controlled by `CONFIG_DO_SINGLE_PASS`)
- When enabled: validates during the first read pass (no second pass needed)
- When disabled: may require a second pass for constraint validation
- Default appears to be **two-pass** (read all, then validate)

## 9. Implications for duckdb-interlis

### For Phase 5 (Current – Cache)
- **Our ModelCache is genuinely useful** – ilivalidator does no caching itself
- Caching `TransferDescription` eliminates redundant ili2c compilation
- The `PipelinePool`/`ObjectPool`/`LinkPool` are not cacheable (data-dependent)

### For Phase 6 (Validator)
- Constraint/AREA validation requires `allObjectsAccessible=true`
- This means full XTF materialization in memory – cannot be avoided for full validation
- The "fast" profile could disable constraints and AREA

### For Phase 14 (Batching/Streaming)
- Streaming is only possible when constraints and AREA are disabled
- `allObjectsAccessible=false` enables per-object streaming validation
- A streaming XTF reader would need to work without the `ObjectPool`
- References between objects (forward references) would need resolution
- Set constraints and global constraints fundamentally cannot be streamed
- Two-phase approach possible: stream for type/multiplicity, then batch for constraints
