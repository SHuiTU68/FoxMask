// libmagiskaudit.so —— 被 ptrace 远程 dlopen 到 logd 进程后，
// 由构造函数安装 vasprintf 的 PLT hook，重写 audit 日志中
// 敏感 SELinux context。无需 ZygiskNext，完全独立。
//
// 该文件编译为独立 .so（参见 native/src/Android.mk 的 B_AUDIT 段），
// 不能依赖 libbase / libmagisk-rs，仅用 libc + liblog + lsplt。

#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <cstdarg>
#include <string_view>
#include <link.h>
#include <sys/auxv.h>
#include <dlfcn.h>

#include <lsplt.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "FoxMask-auditpatch", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FoxMask-auditpatch", __VA_ARGS__)

// 原版 ZN-AuditPatch 的目标 context，参见
// https://android.googlesource.com/platform/system/logging/+/main/logd/LogAudit.cpp
constexpr std::string_view kTargetContext = "tcontext=u:r:priv_app:s0:c512,c768";

// 需要被替换掉的敏感 context（root 痕迹）
constexpr std::string_view kSensitiveContexts[] = {
    "tcontext=u:r:su:s0",
    "tcontext=u:r:magisk:s0",
};

static int (*g_old_vasprintf)(char **strp, const char *fmt, va_list ap) = nullptr;

// 检查匹配位置之后是否还有 `"`（若有，说明 context 已带尾部引号，
// 不是 audit 行内的 substring 误匹配）。这与原版 ZN-AuditPatch 行为一致。
static bool has_quote_after(const char *pos, size_t match_len) {
    const char *end = pos + match_len;
    while (*end != '\0') {
        if (*end == '"') return true;
        ++end;
    }
    return false;
}

static int my_vasprintf(char **strp, const char *fmt, va_list ap) {
    int result = g_old_vasprintf(strp, fmt, ap);
    if (result <= 0 || !*strp)
        return result;

    for (const auto &src : kSensitiveContexts) {
        char *pos = strstr(*strp, src.data());
        if (!pos || has_quote_after(pos, src.size()))
            continue;

        // 计算替换后需要的空间：src -> kTargetContext
        size_t extra = (kTargetContext.size() > src.size())
                       ? (kTargetContext.size() - src.size()) : 0;

        char *new_str = static_cast<char *>(
            malloc(result + 2 * extra + 1));
        if (!new_str)
            return result;  // OOM 时保持原串不变

        strcpy(new_str, *strp);
        pos = new_str + (pos - *strp);

        if (src.size() != kTargetContext.size()) {
            memmove(pos + kTargetContext.size(),
                    pos + src.size(),
                    strlen(pos + src.size()) + 1);
        }
        memcpy(pos, kTargetContext.data(), kTargetContext.size());

        free(*strp);
        *strp = new_str;
        return static_cast<int>(strlen(new_str));
    }
    return result;
}

// 找到 logd 主可执行文件的 base 地址（dl_iterate_phdr 排除 linker）。
static void *find_logd_base() {
    void *base = nullptr;
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *data) -> int {
        auto linker_base = static_cast<uintptr_t>(getauxval(AT_BASE));
        if (linker_base == reinterpret_cast<uintptr_t>(info->dlpi_addr))
            return 0;
        *static_cast<void **>(data) = reinterpret_cast<void *>(info->dlpi_addr);
        return 1;
    }, &base);
    return base;
}

// 安装 vasprintf 的 PLT hook。
// logd 通过 vasprintf 格式化 audit 行，hook 在此处重写敏感 context。
__attribute__((constructor))
static void foxmask_auditpatch_init() {
    void *base = find_logd_base();
    if (!base) {
        LOGE("logd base not found, abort hook");
        return;
    }

    // 通过 lsplt 找到 logd 主映像的 dev/inode，注册 PLT hook。
    // 与原版 ZN-AuditPatch 等价：base 直接对应 logd 主可执行文件。
    bool registered = false;
    for (const auto &map : lsplt::MapInfo::Scan()) {
        if (reinterpret_cast<void *>(map.start) != base)
            continue;
        if (!lsplt::RegisterHook(map.dev, map.inode, "vasprintf",
                                 reinterpret_cast<void *>(my_vasprintf),
                                 reinterpret_cast<void **>(&g_old_vasprintf))) {
            LOGE("RegisterHook failed for vasprintf");
            continue;
        }
        registered = true;
        break;
    }
    if (!registered) {
        LOGE("could not find logd map entry to register hook");
        return;
    }
    if (!lsplt::CommitHook()) {
        LOGE("CommitHook failed");
        return;
    }
    LOGI("vasprintf PLT hook installed in logd");
}
