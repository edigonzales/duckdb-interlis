package ch.so.agi.duckdbili.core.logging;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.logging.LogEvent;
import ch.ehi.basics.logging.LogListener;
import ch.ehi.basics.logging.StdListener;

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
 * suppressed by removing the default {@link StdListener} from the
 * global {@link EhiLogger} singleton and replacing it with a
 * {@link NullLogListener} that silently discards all events.
 * <p>
 * Unlike previous versions, this class no longer redirects
 * {@code System.err}. That was a global operation affecting all
 * threads in the JVM. The listener-based approach is safe for
 * concurrent operations.
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

    private static final NullLogListener NULL_LISTENER = new NullLogListener();

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
     *   <li>A {@link NullLogListener} is registered to capture and discard
     *       any remaining log events.</li>
     * </ul>
     * <p>
     * Calls to {@code suppress()} nest: the listener swap only happens on
     * the first call, and is only restored by the matching {@link #restore()}
     * call.
     * <p>
     * This method is thread-safe and does not affect other threads.
     */
    public static synchronized void suppress() {
        if (DEBUG) return;
        if (suppressDepth == 0) {
            EhiLogger logger = EhiLogger.getInstance();
            logger.removeListener(StdListener.getInstance());
            logger.addListener(NULL_LISTENER);
        }
        suppressDepth++;
    }

    /**
     * Restore EhiLogger stderr output previously suppressed by {@link #suppress()}.
     * <p>
     * The {@link NullLogListener} is removed and {@link StdListener} is re-added
     * only when the outermost nested {@code suppress()}/{@code restore()}
     * pair is closed.
     * <p>
     * This method is thread-safe.
     */
    public static synchronized void restore() {
        if (DEBUG) return;
        if (suppressDepth > 0) {
            suppressDepth--;
            if (suppressDepth == 0) {
                EhiLogger logger = EhiLogger.getInstance();
                logger.removeListener(NULL_LISTENER);
                logger.addListener(StdListener.getInstance());
            }
        }
    }

    private static final class NullLogListener implements LogListener {
        @Override
        public void logEvent(LogEvent event) {
            // discard all log events
        }
    }
}

