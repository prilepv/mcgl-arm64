package local.mcgl.platform;

/** Physical key/action codes retain GLFW's portable numbering; text is separate Unicode. */
public interface InputEvents {
    void key(int key, int action);
    void character(int codepoint);
    void cursorPosition(double x, double y);
    void mouseButton(int button, int action);
    void scroll(double vertical);
    void cursorEntered(boolean entered);
}
