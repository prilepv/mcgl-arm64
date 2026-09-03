#include <jni.h>
#include <CommonCrypto/CommonDigest.h>
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int read_platform_uuid(char *destination, size_t capacity) {
    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault,
                                                        IOServiceMatching("IOPlatformExpertDevice"));
    if (service == IO_OBJECT_NULL)
        return 0;

    CFTypeRef property = IORegistryEntryCreateCFProperty(service,
                                                          CFSTR(kIOPlatformUUIDKey),
                                                          kCFAllocatorDefault,
                                                          0);
    IOObjectRelease(service);
    if (property == NULL || CFGetTypeID(property) != CFStringGetTypeID()) {
        if (property != NULL)
            CFRelease(property);
        return 0;
    }

    Boolean copied = CFStringGetCString((CFStringRef)property,
                                         destination,
                                         (CFIndex)capacity,
                                         kCFStringEncodingUTF8);
    CFRelease(property);
    return copied ? 1 : 0;
}

static jbyteArray make_key_data(JNIEnv *env, jstring login_value, jstring password_value) {
    if (login_value == NULL || password_value == NULL)
        return NULL;

    const char *login = (*env)->GetStringUTFChars(env, login_value, NULL);
    const char *password = (*env)->GetStringUTFChars(env, password_value, NULL);
    if (login == NULL || password == NULL) {
        if (login != NULL)
            (*env)->ReleaseStringUTFChars(env, login_value, login);
        if (password != NULL)
            (*env)->ReleaseStringUTFChars(env, password_value, password);
        return NULL;
    }

    char uuid[128] = {0};
    if (!read_platform_uuid(uuid, sizeof(uuid)))
        snprintf(uuid, sizeof(uuid), "UNKNOWN");

    const char *volume = "8013523602";
    size_t body_capacity = strlen(volume) + strlen(uuid) + strlen(login) + strlen(password) + 64;
    char *body = (char *)calloc(body_capacity, 1);
    snprintf(body, body_capacity,
             "VOLUME:%s,CPU:%s,LOGIN:%s,PASS:%s",
             volume, uuid, login, password);

    unsigned char digest[CC_MD5_DIGEST_LENGTH];
    CC_MD5(body, (CC_LONG)strlen(body), digest);

    char digest_hex[CC_MD5_DIGEST_LENGTH * 2 + 1];
    for (size_t index = 0; index < CC_MD5_DIGEST_LENGTH; index++)
        snprintf(digest_hex + index * 2, 3, "%02x", digest[index]);

    size_t clear_capacity = strlen(body) + sizeof(digest_hex) + 8;
    char *clear = (char *)calloc(clear_capacity, 1);
    snprintf(clear, clear_capacity, "MD5:%s,%s", digest_hex, body);

    const char *mask = "qawsedrf";
    size_t mask_length = strlen(mask);
    size_t clear_length = strlen(clear);
    jbyte *encoded = (jbyte *)malloc(clear_length);
    for (size_t index = 0; index < clear_length; index++)
        encoded[index] = (jbyte)(((unsigned char)clear[index]) ^ ((unsigned char)mask[index % mask_length]));

    jbyteArray result = (*env)->NewByteArray(env, (jsize)clear_length);
    if (result != NULL)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)clear_length, encoded);

    memset(encoded, 0, clear_length);
    memset(clear, 0, clear_capacity);
    memset(body, 0, body_capacity);
    free(encoded);
    free(clear);
    free(body);
    (*env)->ReleaseStringUTFChars(env, login_value, login);
    (*env)->ReleaseStringUTFChars(env, password_value, password);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_net_minecraft_B_xab_getKeyDat(JNIEnv *env, jclass owner, jstring login, jstring password) {
    (void)owner;
    return make_key_data(env, login, password);
}

JNIEXPORT jbyteArray JNICALL
Java_net_minecraft_dmh_AHClient_getKeyDat(JNIEnv *env, jclass owner, jstring login, jstring password) {
    (void)owner;
    return make_key_data(env, login, password);
}
