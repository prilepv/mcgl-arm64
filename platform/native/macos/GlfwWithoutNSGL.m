#include "internal.h"

/* Retain the Cocoa binding symbols while refusing to create an NSGL context. */
GLFWbool _glfwInitNSGL(void)
{
    _glfwInputError(GLFW_API_UNAVAILABLE, "NSGL is unavailable in the Metal runtime");
    return GLFW_FALSE;
}

void _glfwTerminateNSGL(void) {}

GLFWbool _glfwCreateContextNSGL(_GLFWwindow* window,
                                const _GLFWctxconfig* ctxconfig,
                                const _GLFWfbconfig* fbconfig)
{
    return _glfwInitNSGL();
}

GLFWAPI id glfwGetNSGLContext(GLFWwindow* handle)
{
    return nil;
}
