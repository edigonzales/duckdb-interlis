-- Current native MVP example.
SELECT * FROM interlis_components();

SELECT *
FROM ili_models(['testdata/native/introspection.ili']);

SELECT _tid, Name
FROM xtf_scan('testdata/native/simple.xtf',
               'NativeIntrospection.Data.Feature',
               ['testdata/native/introspection.ili']);

SELECT occurrence, value
FROM xtf_values('testdata/native/simple.xtf',
                'NativeIntrospection.Data.Feature',
                'Name',
                ['testdata/native/introspection.ili']);

SELECT *
FROM xtf_set('testdata/native/simple.xtf',
             '/tmp/duckdb-interlis-native-mvp-updated.xtf',
             'NativeIntrospection.Data.Feature',
             'F1',
             'Name',
             'updated',
             ['testdata/native/introspection.ili'],
             overwrite := true);
