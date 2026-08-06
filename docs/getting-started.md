# Getting started

Load the native extension into DuckDB 1.5.3:

```sh
duckdb -unsigned
```

```sql
LOAD '/path/to/interlis.duckdb_extension';
SELECT * FROM interlis_components();
```

Compile a local model and inspect its classes:

```sql
SELECT * FROM ili_classes(
  ['/path/to/model.ili'],
  model := 'MyModel'
);
```

Read one class from a local XTF file:

```sql
SELECT _tid, Name, Geometry
FROM xtf_scan('/path/to/data.xtf',
               'MyModel.Data.Feature',
               ['/path/to/model.ili']);
```

Read a nested primitive value:

```sql
SELECT *
FROM xtf_values('/path/to/data.xtf',
                'MyModel.Data.Feature',
                'Details.Label',
                ['/path/to/model.ili']);
```

Write one primitive value to a new transfer:

```sql
SELECT *
FROM xtf_set('/path/to/data.xtf',
             '/path/to/data-updated.xtf',
             'MyModel.Data.Feature',
             'F1', 'Name', 'updated',
             ['/path/to/model.ili']);
```

The MVP does not validate data, resolve remote models, support `ATTACH`, or
modify a transfer in place.
