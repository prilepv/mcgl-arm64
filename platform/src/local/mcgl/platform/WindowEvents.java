package local.mcgl.platform;

/** Invoked on the event thread; callbacks must not acquire legacy input locks. */
public interface WindowEvents {
    void resized();
    void focusLost();
}
