/**
 * DelosDB engine-kernel extraction target for Derby-compatible services that
 * must be shared by the engine and the independently compiled legacy store.
 *
 * <p>B1 intentionally moves no packages. Later B-steps will move whole kernel
 * packages here behind proof-gated overlays.</p>
 */
module io.github.ggeorg.delosdb.engine.kernel {
    requires java.base;
    requires org.apache.derby.commons;

    exports org.apache.derby.io;
    exports org.apache.derby.iapi.services.context to
        org.apache.derby.engine,
        org.apache.derby.optionaltools,
        org.apache.derby.tests;
}
