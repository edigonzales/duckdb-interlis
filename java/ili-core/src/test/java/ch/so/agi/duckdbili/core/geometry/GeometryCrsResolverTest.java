package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ilirepository.IliManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeometryCrsResolverTest {

    private static final Path TESTDIR;
    static {
        Path cwd = Path.of("").toAbsolutePath();
        Path dir = cwd.resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        if (!Files.isRegularFile(dir)) {
            dir = cwd.getParent().resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        }
        if (!Files.isRegularFile(dir)) {
            dir = cwd.getParent().getParent().resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        }
        if (!Files.isRegularFile(dir)) {
            dir = cwd.resolve("ili-core/testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        }
        TESTDIR = dir.getParent();
    }

    private static TransferDescription TD;

    @BeforeAll
    static void compileModel() throws Exception {
        assertTrue(Files.isDirectory(TESTDIR), "test directory not found: " + TESTDIR);
        IliManager manager = new IliManager();
        manager.setRepositories(new String[]{TESTDIR.toString(), "https://models.interlis.ch"});
        java.util.ArrayList<String> entries = new java.util.ArrayList<>();
        entries.add(TESTDIR.resolve("SO_AGI_Geometries_20260605.ili").toString());
        Configuration cfg = manager.getConfigWithFiles(entries, null, 0.0);
        Ili2cSettings settings = new Ili2cSettings();
        Main.setDefaultIli2cPathMap(settings);
        settings.setIlidirs(TESTDIR.toString());
        TD = Main.runCompiler(cfg, settings, null);
        assertNotNull(TD, "model compilation failed");
    }

    @Test
    void noopReturnsEmpty() {
        GeometryCrsResolver resolver = new NoopGeometryCrsResolver();
        GeometryMetadataContext ctx = new GeometryMetadataContext(
                "M", "T", "C", "A", "M.Koord");
        assertEquals(Optional.empty(), resolver.resolve(ctx));
    }

    @Test
    void mapResolverResolvesByDomainFqn() {
        Map<String, CrsIdentifier> map = Map.of(
                "SO_AGI_Model.Koord", new CrsIdentifier("EPSG", "2056", 2056));
        GeometryCrsResolver resolver = new MapGeometryCrsResolver(map);
        GeometryMetadataContext ctx = new GeometryMetadataContext(
                "SO_AGI_Model", "Topic", "Class", "geom", "SO_AGI_Model.Koord");
        Optional<CrsIdentifier> crs = resolver.resolve(ctx);
        assertTrue(crs.isPresent());
        assertEquals("EPSG", crs.get().authority());
        assertEquals("2056", crs.get().code());
        assertEquals(2056, crs.get().srid());
    }

    @Test
    void metadataServiceListsGeometryAttributes() {
        InterlisGeometryTypeResolver typeResolver = new InterlisGeometryTypeResolver();
        GeometryCrsResolver crsResolver = new NoopGeometryCrsResolver();
        GeometryAttributeMetadataService service =
                new GeometryAttributeMetadataService(typeResolver, crsResolver);

        List<GeometryMetadata> attrs = service.listGeometryAttributes(TD, null, null);
        assertFalse(attrs.isEmpty());

        // Filter to our test model
        List<GeometryMetadata> ours = attrs.stream()
                .filter(m -> m.modelName().equals("SO_AGI_Geometries_20260605"))
                .toList();
        assertFalse(ours.isEmpty());

        // Check sorting: class name then attribute name
        for (int i = 1; i < ours.size(); i++) {
            assertTrue(ours.get(i).className().compareTo(ours.get(i - 1).className()) >= 0);
        }

        // Verify some known types
        boolean hasPoint = ours.stream().anyMatch(m -> m.geometryKind() == GeometryKind.POINT);
        boolean hasPolygon = ours.stream().anyMatch(m -> m.geometryKind() == GeometryKind.POLYGON);
        boolean hasArea = ours.stream().anyMatch(m -> m.geometryKind() == GeometryKind.AREA);
        assertTrue(hasPoint, "should have POINT");
        assertTrue(hasPolygon, "should have POLYGON");
        assertTrue(hasArea, "should have AREA");
    }

    @Test
    void metadataServiceWithCrsMapping() {
        Map<String, CrsIdentifier> map = Map.of(
                "SO_AGI_Geometries_20260605.Koord", new CrsIdentifier("EPSG", "2056", 2056));
        InterlisGeometryTypeResolver typeResolver = new InterlisGeometryTypeResolver();
        GeometryCrsResolver crsResolver = new MapGeometryCrsResolver(map);
        GeometryAttributeMetadataService service =
                new GeometryAttributeMetadataService(typeResolver, crsResolver);

        List<GeometryMetadata> attrs = service.listGeometryAttributes(TD, null, null);
        List<GeometryMetadata> ours = attrs.stream()
                .filter(m -> m.modelName().equals("SO_AGI_Geometries_20260605"))
                .toList();

        // The 'Koord' domain is used by many geometry attributes
        long withEpsg = ours.stream().filter(m -> "EPSG".equals(m.crsAuthName())).count();
        assertTrue(withEpsg > 0, "at least some attributes should have EPSG mapping");
    }
}
