# Architecture

The DuckDB ILI extension consists of three layers:

## Layers

```
┌─────────────────────────────┐
│   DuckDB SQL Interface      │  ◄── SQL functions & table functions
├─────────────────────────────┤
│   DuckDB Extension (C)      │  ◄── ili_extension.c (C API extension)
├─────────────────────────────┤
│   Native Bridge             │  ◄── dynamic loading + call translation
├─────────────────────────────┤
│   GraalVM Native Library    │  ◄── libduckdb_ili_native.dylib (C ABI)
├─────────────────────────────┤
│   Java Core                 │  ◄── ili-core (business logic)
└─────────────────────────────┘
```

## Compile Flow

1. `java/ili-core` → Gradle compiles Java business logic
2. `java/ili-native` → GraalVM native-image compiles to shared library (.dylib)
3. `duckdb-extension` → CMake compiles C extension that loads the native library

## Runtime Flow

1. DuckDB `LOAD 'interlis.duckdb_extension'` loads the C extension
2. Extension registers SQL functions via DuckDB C API
3. SQL function calls invoke the native library functions
4. Native library executes Java business logic via GraalVM compiled code
5. Results flow back as C strings (JSON), parsed by the extension
