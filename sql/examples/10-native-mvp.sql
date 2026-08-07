-- Complete native MVP example. Run with:
--   scripts/dev-duckdb.sh < sql/examples/10-native-mvp.sql
SELECT interlis_version();

SELECT * FROM interlis_components();

SELECT *
FROM ili_models(['testdata/native/introspection.ili']);

SELECT *
FROM ili_classes(['testdata/native/introspection.ili'], model := 'NativeIntrospection');

SELECT *
FROM ili_properties('NativeIntrospection.Data.Feature',
                     ['testdata/native/introspection.ili']);

SELECT *
FROM ili_geometry_properties('NativeIntrospection.Data.Feature',
                              ['testdata/native/introspection.ili']);

SELECT _tid, Name
FROM xtf_scan('testdata/native/simple.xtf',
               'NativeIntrospection.Data.Feature',
               ['testdata/native/introspection.ili'],
               geometry_errors := 'null');

SELECT bid, tid, class_name, occurrence, value
FROM xtf_values('testdata/native/simple.xtf',
                'NativeIntrospection.Data.Feature',
                'Name',
                ['testdata/native/introspection.ili'],
                tid := 'F1',
                bid := 'B1');

SELECT tid, occurrence, value
FROM xtf_values('testdata/native/scan.xtf',
                'NativeScan.Data.Feature',
                'Tags[*].Value',
                ['testdata/native/scan.ili'],
                tid := 'F1',
                bid := 'B1');

SELECT *
FROM xtf_set('testdata/native/simple.xtf',
             '/tmp/duckdb-interlis-native-mvp-updated.xtf',
             'NativeIntrospection.Data.Feature',
             'F1',
             'Name',
             'updated',
             ['testdata/native/introspection.ili'],
             bid := 'B1',
             expected := 'alpha',
             overwrite := true);
