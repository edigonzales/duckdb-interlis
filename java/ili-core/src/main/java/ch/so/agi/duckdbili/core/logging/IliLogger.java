package ch.so.agi.duckdbili.core.logging;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.logging.StdListener;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Controls ili2c / ilivalidator logging output.
 * <p>
 * The ili2c compiler and ilivalidator use {@link EhiLogger} (via
 * {@link StdListener}) to write progress, state, and error messages to
 * stderr. In a DuckDB extension context these messages pollute the
 * terminal and are confusing because validation results are already
 * returned as structured query data.
 * <p>
 * Set the environment variable {@code DUCKDB_ILI_DEBUG=1} to see the
 * raw ili2c/ilivalidator diagnostics. When the variable is absent or
 * set to any value other than {@code "1"}, all EhiLogger output is
 * suppressed by:
 * <ol>
 *   <li>Removing the default {@link StdListener} from the global
 *       {@link EhiLogger} singleton.</li>
 *   <li>Redirecting {@code System.err} to a null output stream as a
 *       belt-and-suspenders measure, since ili2c/ilivalidator may
 *       re-add listeners or write directly to stderr.</li>
 * </ol>
 * <p>
 * Usage in service code (try/finally pattern):
 * <pre>{@code
 * IliLogger.suppress();
 * try {
 *     // ili2c / ilivalidator call that uses EhiLogger
 * } finally {
 *     IliLogger.restore();
 * }
 * }</pre>
 */
public final class IliLogger {

    private static final boolean DEBUG = "1".equals(System.getenv("DUCKDB_ILI_DEBUG"));

    private static final PrintStream NULL_STREAM = new PrintStream(OutputStream.nullOutputStream());

    private static PrintStream originalErr;
    private static boolean originalErrSaved;

    private static int suppressDepth = 0;

    private IliLogger() {}

    /**
     * Returns whether debug diagnostics should be written to stderr.
     */
    public static boolean isDebugEnabled() {
        return DEBUG;
    }

    /**
     * Suppress EhiLogger stderr output if {@code DUCKDB_ILI_DEBUG} is not set.
     * <p>
     * When debug mode is off:
     * <ul>
     *   <li>The default {@link StdListener} is removed from the global
     *       {@link EhiLogger} singleton.</li>
     *   <li>{@code System.err} is redirected to a null stream to catch
     *       any output that bypasses EhiLogger.</li>
     * </ul>
     * <p>
     * Calls to {@code suppress()} nest: the listener is only removed and
     * stderr redirected on the first call, and only restored by the
     * matching {@link #restore()} call.
     */
    public static synchronized void suppress() {
        if (DEBUG) return;
        if (suppressDepth == 0) {
            saveOriginalErr();
            System.setErr(NULL_STREAM);
            EhiLogger.getInstance().removeListener(StdListener.getInstance());
        }
        suppressDepth++;
    }

    /**
     * Restore EhiLogger stderr output previously suppressed by {@link #suppress()}.
     * <p>
     * {@link StdListener} is re-added and the original {@code System.err} stream
     * is restored only when the outermost nested {@code suppress()}/{@code restore()}
     * pair is closed.
     */
    public static synchronized void restore() {
        if (DEBUG) return;
        if (suppressDepth > 0) {
            suppressDepth--;
            if (suppressDepth == 0) {
                if (originalErrSaved) {
                    System.setErr(originalErr);
                }
                EhiLogger.getInstance().addListener(StdListener.getInstance());
            }
        }
    }

    private static void saveOriginalErr() {
        if (!originalErrSaved) {
            originalErr = System.err;
            originalErrSaved = true;
        }
    }
}

