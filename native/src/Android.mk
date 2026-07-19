LOCAL_PATH := $(call my-dir)

########################
# Binaries
########################

ifdef B_MAGISK

include $(CLEAR_VARS)
LOCAL_MODULE := magisk
LOCAL_STATIC_LIBRARIES := \
    libbase \
    libsystemproperties \
    liblsplt \
    libmagisk-rs

LOCAL_SRC_FILES := \
    core/applets.cpp \
    core/scripting.cpp \
    core/sqlite.cpp \
    core/utils.cpp \
    core/core-rs.cpp \
    core/resetprop/sys.cpp \
    core/su/su.cpp \
    core/zygisk/entry.cpp \
    core/zygisk/module.cpp \
    core/zygisk/hook.cpp \
    core/deny/cli.cpp \
    core/deny/utils.cpp \
    core/deny/logcat.cpp \
    core/auditpatch/auditpatch.cpp

LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,--dynamic-list=src/exported_sym.txt

include $(BUILD_EXECUTABLE)

endif

ifdef B_PRELOAD

include $(CLEAR_VARS)
LOCAL_MODULE := init-ld
LOCAL_SRC_FILES := init/preload.c
LOCAL_LDFLAGS := -Wl,--strip-all
include $(BUILD_SHARED_LIBRARY)

endif

ifdef B_AUDIT

# libmagiskaudit.so —— 被 ptrace 远程 dlopen 到 logd 后由构造函数
# PLT-hook vasprintf，重写 audit 日志中的 root context。
# 必须是共享库，且依赖 lsplt（与 magisk 主二进制共用）。
# 仅 aarch64 编译：32 位架构不支持 ptrace 注入，不需要此 .so。
ifeq ($(TARGET_ARCH),arm64)
include $(CLEAR_VARS)
LOCAL_MODULE := libmagiskaudit
LOCAL_STATIC_LIBRARIES := liblsplt
LOCAL_SRC_FILES := core/auditpatch/hook.cpp
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -fvisibility=hidden -fvisibility-inlines-hidden
LOCAL_LDFLAGS := -Wl,--strip-all
include $(BUILD_SHARED_LIBRARY)
endif  # arm64

endif

ifdef B_INIT

include $(CLEAR_VARS)
LOCAL_MODULE := magiskinit
LOCAL_STATIC_LIBRARIES := \
    libbase \
    libpolicy \
    libxz \
    libinit-rs

LOCAL_SRC_FILES := \
    init/mount.cpp \
    init/rootdir.cpp \
    init/getinfo.cpp \
    init/init-rs.cpp

LOCAL_LDFLAGS := -static

ifdef B_CRT0
LOCAL_STATIC_LIBRARIES += crt0
LOCAL_LDFLAGS += -Wl,--defsym=vfprintf=tiny_vfprintf
endif

include $(BUILD_EXECUTABLE)

endif

ifdef B_BOOT

include $(CLEAR_VARS)
LOCAL_MODULE := magiskboot
LOCAL_STATIC_LIBRARIES := \
    libbase \
    liblz4 \
    libboot-rs

LOCAL_SRC_FILES := \
    boot/bootimg.cpp \
    boot/boot-rs.cpp

LOCAL_LDFLAGS := -static

ifdef B_CRT0
LOCAL_STATIC_LIBRARIES += crt0
LOCAL_LDFLAGS += -lm -Wl,--defsym=vfprintf=musl_vfprintf
endif

include $(BUILD_EXECUTABLE)

endif

ifdef B_POLICY

include $(CLEAR_VARS)
LOCAL_MODULE := magiskpolicy
LOCAL_STATIC_LIBRARIES := \
    libbase \
    libpolicy \
    libpolicy-rs

include $(BUILD_EXECUTABLE)

endif

ifdef B_PROP

include $(CLEAR_VARS)
LOCAL_MODULE := resetprop
LOCAL_STATIC_LIBRARIES := \
    libbase \
    libsystemproperties \
    libmagisk-rs

LOCAL_SRC_FILES := \
    core/applet_stub.cpp \
    core/resetprop/sys.cpp \
    core/core-rs.cpp

LOCAL_CFLAGS := -DAPPLET_STUB_MAIN=resetprop_main
include $(BUILD_EXECUTABLE)

endif

########################
# Libraries
########################

include $(CLEAR_VARS)
LOCAL_MODULE := libpolicy
LOCAL_STATIC_LIBRARIES := \
    libbase \
    libsepol
LOCAL_C_INCLUDES := src/sepolicy/include
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_C_INCLUDES)
LOCAL_SRC_FILES := \
    sepolicy/api.cpp \
    sepolicy/sepolicy.cpp \
    sepolicy/policydb.cpp \
    sepolicy/policy-rs.cpp
include $(BUILD_STATIC_LIBRARY)

CWD := $(LOCAL_PATH)
include $(CWD)/Android-rs.mk
include $(CWD)/base/Android.mk
include $(CWD)/external/Android.mk
