/**
 * Modular so jpackage/jlink can build a minimal bundled runtime with JavaFX
 * linked in. The ui package is exported only to javafx.graphics, which
 * instantiates the Application subclass reflectively.
 */
module com.kodewerk.diskinventory {
    requires javafx.controls;

    exports com.kodewerk.diskinventory.ui to javafx.graphics;
}
