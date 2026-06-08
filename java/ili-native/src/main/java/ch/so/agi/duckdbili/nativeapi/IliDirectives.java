package ch.so.agi.duckdbili.nativeapi;

import java.util.List;

import org.graalvm.nativeimage.c.CContext;

public final class IliDirectives implements CContext.Directives {

    @Override
    public List<String> getHeaderFiles() {
        return List.of("\"ili_request.h\"");
    }

    @Override
    public List<String> getLibraries() {
        return List.of();
    }

    @Override
    public List<String> getOptions() {
        return List.of();
    }
}
