package ch.so.agi.duckdbili.nativeapi;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.graalvm.nativeimage.c.CContext;

public final class IliDirectives implements CContext.Directives {

    @Override
    public List<String> getHeaderFiles() {
        return Collections.singletonList("\"ili_request.h\"");
    }

    @Override
    public List<String> getLibraries() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOptions() {
        return Collections.emptyList();
    }
}
