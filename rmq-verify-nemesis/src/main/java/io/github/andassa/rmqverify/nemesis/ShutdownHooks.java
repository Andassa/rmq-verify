package io.github.andassa.rmqverify.nemesis;

/** Registration of JVM shutdown hooks; a seam so that tests do not touch the real runtime. */
public interface ShutdownHooks {

  /**
   * Registers a hook.
   *
   * @param hook thread run at JVM shutdown
   */
  void register(Thread hook);

  /**
   * Removes a hook; does nothing if the JVM is already shutting down.
   *
   * @param hook a registered hook
   */
  void unregister(Thread hook);

  /**
   * Returns the hooks of the current JVM.
   *
   * @return the runtime-backed implementation
   */
  static ShutdownHooks runtime() {
    return new ShutdownHooks() {
      @Override
      public void register(Thread hook) {
        Runtime.getRuntime().addShutdownHook(hook);
      }

      @Override
      public void unregister(Thread hook) {
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
          // The JVM is shutting down and the hook is running or about to run: nothing to undo.
          return;
        }
      }
    };
  }
}
