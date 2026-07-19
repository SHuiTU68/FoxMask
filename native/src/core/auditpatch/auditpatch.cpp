// FoxMask AuditPatch —— 主实现：ptrace 远程 dlopen 注入 logd，
// 以及 CLI / daemon 端的 enable/disable/status/inject 处理。
//
// 不依赖 ZygiskNext，所有注入逻辑由本文件实现。

#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/mman.h>
#include <sys/user.h>
#include <elf.h>
#include <link.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <string_view>
#include <vector>
#include <fstream>
#include <sstream>
#include <atomic>

#include <consts.hpp>
#include <base.hpp>
#include <core.hpp>

#include "auditpatch.hpp"

using namespace std;

// ============================================================================
// DB 状态
// ============================================================================

static atomic<bool> auditpatch_enforced{false};

static const char *libmagiskaudit_path() {
    // libmagiskaudit.so 与 magisk 二进制同目录（/data/adb/magisk/）
    static string path;
    if (path.empty()) {
        path = string(DATABIN) + "/libmagiskaudit.so";
    }
    return path.c_str();
}

// ============================================================================
// ptrace 远程 dlopen 注入
// ============================================================================
//
// 标准模式：
//   1. PTRACE_ATTACH + waitpid
//   2. PTRACE_GETREGS 保存现场
//   3. 解析 /proc/<pid>/maps 得到目标 libc base
//   4. 用本进程 dlsym(dlopen) - 本进程 libc base 得到 dlopen 偏移
//      （Android API 26+ 上 dlopen 实际在 libc.so 内）
//   5. 远程调用 mmap 取一块可写内存
//   6. PTRACE_POKEDATA 把 .so 路径写入
//   7. 远程调用 dlopen(path, RTLD_NOW)
//   8. PTRACE_SETREGS 恢复现场
//   9. PTRACE_DETACH
//
// 支持 arm64 与 x86_64。

#if defined(__aarch64__)
using RegSet = struct user_pt_regs;
constexpr int kRegSetOp = PTRACE_GETREGSET;
constexpr unsigned long kRegSetType = NT_PRSTATUS;

static inline void *_reg_pc(RegSet &r) { return reinterpret_cast<void *>(r.pc); }
static inline void _set_reg_pc(RegSet &r, void *p) { r.pc = reinterpret_cast<uintptr_t>(p); }
static inline void *_reg_sp(RegSet &r) { return reinterpret_cast<void *>(r.sp); }
static inline void _set_reg_sp(RegSet &r, void *p) { r.sp = reinterpret_cast<uintptr_t>(p); }
// user_pt_regs.regs[] 在 aarch64 NDK 是 __u64（unsigned long long），
// 访问器必须返回 __u64& 才能直接绑定（uint64_t 是 unsigned long，类型不同）
static inline __u64 &_reg_ret(RegSet &r) { return r.regs[0]; }
static inline __u64 &_reg_arg0(RegSet &r) { return r.regs[0]; }
static inline __u64 &_reg_arg1(RegSet &r) { return r.regs[1]; }
static inline __u64 &_reg_arg2(RegSet &r) { return r.regs[2]; }
static inline __u64 &_reg_arg3(RegSet &r) { return r.regs[3]; }
static inline __u64 &_reg_arg4(RegSet &r) { return r.regs[4]; }
static inline __u64 &_reg_arg5(RegSet &r) { return r.regs[5]; }
static inline __u64 &_reg_lr(RegSet &r) { return r.regs[30]; }

#else
#  error "auditpatch: unsupported architecture (only aarch64 is supported)"
#endif

static bool ptrace_get_regs(int pid, RegSet *regs) {
    struct iovec iov { regs, sizeof(*regs) };
    return ptrace(kRegSetOp, pid, reinterpret_cast<void *>(kRegSetType), &iov) == 0;
}

static bool ptrace_set_regs(int pid, RegSet *regs) {
    struct iovec iov { regs, sizeof(*regs) };
    return ptrace(PTRACE_SETREGSET, pid, reinterpret_cast<void *>(kRegSetType), &iov) == 0;
}

// 读取 /proc/<pid>/maps，找到第一行 path == "/system/lib64/libc.so"（或 32 位变体）的 start 地址
static void *find_remote_lib_base(int pid, const char *lib_name) {
    char path[64];
    ssprintf(path, sizeof(path), "/proc/%d/maps", pid);
    ifstream f(path);
    if (!f)
        return nullptr;
    string line;
    while (getline(f, line)) {
        // 形如：address           perms offset  dev   inode      pathname
        // 7e8b3d4000-7e8b3d5000 r--p 00000000 fe:00 1234        /system/lib64/libc.so
        if (line.find(lib_name) == string::npos)
            continue;
        // 必须是 path 字段结尾匹配，不是 substring
        auto last_space = line.rfind(' ');
        if (last_space == string::npos)
            continue;
        string path_field = line.substr(last_space + 1);
        if (path_field != lib_name)
            continue;
        // 解析 start 地址
        auto dash = line.find('-');
        if (dash == string::npos)
            continue;
        return reinterpret_cast<void *>(strtoull(line.substr(0, dash).c_str(), nullptr, 16));
    }
    return nullptr;
}

// 本进程 libc base（用于计算 dlopen 偏移）
static void *find_local_lib_base(const char *lib_name) {
    void *base = nullptr;
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *data) -> int {
        const char *name = info->dlpi_name;
        if (!name || !*name)
            return 0;
        // 简单后缀匹配
        std::string_view n(name);
        std::string_view want = *static_cast<std::string_view *>(data);
        if (n.size() >= want.size() && n.substr(n.size() - want.size()) == want) {
            *static_cast<void **>(data) = reinterpret_cast<void *>(info->dlpi_addr);
            return 1;
        }
        return 0;
    }, &base);
    return base;
}

// 计算目标进程中函数 func_name 的地址（基于 libc 偏移）
static void *resolve_remote_func(int pid, const char *lib_name, const char *func_name) {
    void *local_base = find_local_lib_base(lib_name);
    if (!local_base)
        return nullptr;
    void *local_func = dlsym(RTLD_DEFAULT, func_name);
    if (!local_func)
        local_func = dlsym(RTLD_NEXT, func_name);
    if (!local_func)
        return nullptr;
    uintptr_t offset = reinterpret_cast<uintptr_t>(local_func) -
                       reinterpret_cast<uintptr_t>(local_base);
    void *remote_base = find_remote_lib_base(pid, lib_name);
    if (!remote_base)
        return nullptr;
    return reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(remote_base) + offset);
}

// 远程调用：1-3 个参数；通过 mmap 一段 trampoline 不必要，
// 直接复用目标栈：把 LR/RA 设为 0，调用完触发 SIGSEGV，
// 我们捕获后读返回值。这是 ptrace 注入的标准技巧。
//
// 注意：arm64 与 x86_64 都支持「返回到 0 地址触发 SIGSEGV」。
//
// 返回 true 表示调用完成且未崩溃；调用结果写入 *out_ret。
// 支持最多 6 个参数（aarch64 ABI: x0-x5），覆盖 mmap/dlopen 等常用 libc 函数。
static bool remote_call(int pid, RegSet *regs, void *func,
                        __u64 arg0, __u64 arg1, __u64 arg2,
                        __u64 arg3, __u64 arg4, __u64 arg5,
                        __u64 *out_ret) {
    RegSet saved = *regs;

    // 设置参数寄存器
    _reg_arg0(*regs) = arg0;
    _reg_arg1(*regs) = arg1;
    _reg_arg2(*regs) = arg2;
    _reg_arg3(*regs) = arg3;
    _reg_arg4(*regs) = arg4;
    _reg_arg5(*regs) = arg5;
#if defined(__aarch64__)
    _reg_lr(*regs) = 0;             // 返回到 0 触发 SIGSEGV
    _set_reg_pc(*regs, func);
    // SP 不变（复用目标栈）
#endif

    if (!ptrace_set_regs(pid, regs))
        return false;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0)
        return false;

    int status = 0;
    if (waitpid(pid, &status, 0) < 0)
        return false;
    if (!WIFSTOPPED(status) || WSTOPSIG(status) != SIGSEGV) {
        // 调用没有按预期在 LR=0 处崩溃 —— 视为失败
        return false;
    }
    // 读回返回值
    RegSet after{};
    if (!ptrace_get_regs(pid, &after))
        return false;
    if (out_ret)
        *out_ret = _reg_ret(after);

    // 恢复现场
    *regs = saved;
    return ptrace_set_regs(pid, regs);
}

// 把 data 写入目标进程的 addr 处（PTRACE_POKEDATA 一次写 sizeof(long)）
static bool write_remote(int pid, uintptr_t addr, const void *data, size_t len) {
    const auto *p = static_cast<const unsigned char *>(data);
    size_t i = 0;
    while (i + sizeof(long) <= len) {
        long val;
        memcpy(&val, p + i, sizeof(long));
        if (ptrace(PTRACE_POKEDATA, pid,
                   reinterpret_cast<void *>(addr + i),
                   reinterpret_cast<void *>(val)) != 0)
            return false;
        i += sizeof(long);
    }
    if (i < len) {
        // 尾部不足 sizeof(long)，先 PEEK 再改后 POKE
        long val = ptrace(PTRACE_PEEKDATA, pid,
                          reinterpret_cast<void *>(addr + i), nullptr);
        if (val == -1 && errno)
            return false;
        memcpy(&val, p + i, len - i);
        if (ptrace(PTRACE_POKEDATA, pid,
                   reinterpret_cast<void *>(addr + i),
                   reinterpret_cast<void *>(val)) != 0)
            return false;
    }
    return true;
}

// 定位 logd 的 PID
static int find_logd_pid() {
    DIR *dir = opendir("/proc");
    if (!dir)
        return -1;
    int found = -1;
    struct dirent *de;
    while ((de = readdir(dir))) {
        int pid = atoi(de->d_name);
        if (pid <= 0)
            continue;
        char path[64];
        ssprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            continue;
        char buf[64] = {0};
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0 && strcmp(buf, "logd") == 0) {
            found = pid;
            break;
        }
    }
    closedir(dir);
    return found;
}

#if defined(__LP64__)
#  define REMOTE_LIBC "/system/lib64/libc.so"
#else
#  define REMOTE_LIBC "/system/lib/libc.so"
#endif

int auditpatch_inject_into_logd() {
    const char *lib_path = libmagiskaudit_path();
    if (access(lib_path, F_OK) != 0) {
        LOGE("auditpatch: %s not found\n", lib_path);
        return AuditPatchResponse::INJECT_FAILED;
    }

    int pid = find_logd_pid();
    if (pid <= 0) {
        LOGE("auditpatch: logd not running\n");
        return AuditPatchResponse::INJECT_FAILED;
    }

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != 0) {
        LOGE("auditpatch: PTRACE_ATTACH pid=%d failed: %s\n", pid, strerror(errno));
        return AuditPatchResponse::INJECT_FAILED;
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0 || !WIFSTOPPED(status)) {
        LOGE("auditpatch: waitpid attach failed\n");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }

    RegSet regs{};
    if (!ptrace_get_regs(pid, &regs)) {
        LOGE("auditpatch: GETREGS failed\n");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }

    void *remote_mmap = resolve_remote_func(pid, REMOTE_LIBC, "mmap");
    void *remote_dlopen = resolve_remote_func(pid, REMOTE_LIBC, "dlopen");
    if (!remote_mmap || !remote_dlopen) {
        LOGE("auditpatch: resolve mmap/dlopen failed (mmap=%p dlopen=%p)\n",
             remote_mmap, remote_dlopen);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }

    // 1) 远程 mmap 取一块可写内存
    __u64 map_ret = 0;
    if (!remote_call(pid, &regs, remote_mmap,
                     0,                                  // addr=NULL
                     4096,                               // len
                     PROT_READ | PROT_WRITE,             // prot
                     MAP_PRIVATE | MAP_ANONYMOUS,        // flags
                     0,                                  // fd=-1 (MAP_ANONYMOUS 忽略)
                     0,                                  // offset=0
                     &map_ret)) {
        LOGE("auditpatch: remote mmap call failed\n");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }
    if (map_ret == static_cast<__u64>(-1) || map_ret == 0) {
        LOGE("auditpatch: remote mmap returned %llx\n", (unsigned long long)map_ret);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }

    // 2) 把 .so 路径写入目标
    string path_str = lib_path;
    path_str.push_back('\0');
    if (!write_remote(pid, static_cast<uintptr_t>(map_ret),
                      path_str.data(), path_str.size())) {
        LOGE("auditpatch: write_remote path failed\n");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }

    // 3) 远程 dlopen(path, RTLD_NOW)
    __u64 dlopen_ret = 0;
    if (!remote_call(pid, &regs, remote_dlopen,
                     map_ret,                            // arg0 = path
                     RTLD_NOW,                           // arg1 = mode
                     0, 0, 0, 0,                         // arg2-5 unused
                     &dlopen_ret)) {
        LOGE("auditpatch: remote dlopen call failed\n");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }
    if (dlopen_ret == 0) {
        LOGE("auditpatch: remote dlopen returned NULL (lib not loadable)\n");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return AuditPatchResponse::INJECT_FAILED;
    }

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
    LOGI("auditpatch: injected libmagiskaudit.so into logd (pid=%d)\n", pid);
    return 0;
}

// ============================================================================
// 启用 / 禁用 / 注入
// ============================================================================

// 应用 cgroup_v2 deny 规则。失败不阻断主流程。
static void apply_sepolicy_rules() {
    // 直接调用 magiskpolicy --live 加两条 deny 规则
    // 使用 execve 避免拉起 shell
    const char *rule_text =
        "deny appdomain cgroup_v2 dir search\n"
        "deny app_zygote cgroup_v2 dir search\n";

    // 写到临时文件再 --apply，避免命令行参数过长
    const char *tmp_rule = "/dev/foxmask_auditpatch.rule";
    int fd = open(tmp_rule, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0)
        return;
    write(fd, rule_text, strlen(rule_text));
    close(fd);

    char *argv[] = {
        const_cast<char *>("magiskpolicy"),
        const_cast<char *>("--live"),
        const_cast<char *>("--apply"),
        const_cast<char *>(tmp_rule),
        nullptr,
    };
    pid_t pid = fork();
    if (pid == 0) {
        execv("/data/adb/magisk/magiskpolicy", argv);
        // fallback: 试 tmp 路径
        execv("/sbin/magiskpolicy", argv);
        _exit(127);
    } else if (pid > 0) {
        int st;
        waitpid(pid, &st, 0);
    }
    unlink(tmp_rule);
}

// 重启 logd（ctl.restart）。logd 重新启动后我们立即注入。
static void restart_logd() {
    // 与原版 ZN-AuditPatch 一致：重置 sys.boot_completed 并重启 logd
    // 这里只重启 logd，不动 sys.boot_completed，避免触发 boot 流程误判
    int pid = fork();
    if (pid == 0) {
        execl("/system/bin/setprop", "setprop", "ctl.restart", "logd",
              (char *)NULL);
        _exit(127);
    } else if (pid > 0) {
        int st;
        waitpid(pid, &st, 0);
    }
    // logd 重启需要一点时间，等最多 2 秒
    for (int i = 0; i < 20; ++i) {
        if (find_logd_pid() > 0)
            break;
        usleep(100 * 1000);
    }
    // 再等一会儿让 logd 完成初始化
    usleep(300 * 1000);
}

int auditpatch_enable() {
    auditpatch_enforced.store(true);
    MagiskD::Get().set_db_setting(DbEntryKey::AuditPatchConfig, 1);
    apply_sepolicy_rules();
    // 立即注入一次，避免用户必须等下次重启 logd
    restart_logd();
    int rc = auditpatch_inject_into_logd();
    if (rc != 0) {
        // 注入失败也不回滚 DB 标志，下次 boot 时再尝试
        LOGW("auditpatch: initial inject failed rc=%d, will retry on next logd restart\n", rc);
    }
    return rc == 0 ? AuditPatchResponse::OK : AuditPatchResponse::INJECT_FAILED;
}

int auditpatch_disable() {
    auditpatch_enforced.store(false);
    MagiskD::Get().set_db_setting(DbEntryKey::AuditPatchConfig, 0);
    // 不主动重启 logd：当前注入的 hook 仍会运行到 logd 下次重启为止。
    // 这样禁用不会突然让 audit 日志重新带 root context（向用户暴露）。
    LOGI("auditpatch: disabled (hook active until next logd restart)\n");
    return AuditPatchResponse::OK;
}

bool auditpatch_enabled() {
    return auditpatch_enforced.load();
}

void initialize_auditpatch() {
    if (auditpatch_enforced)
        return;
    if (MagiskD::Get().get_db_setting(DbEntryKey::AuditPatchConfig)) {
        auditpatch_enforced.store(true);
        apply_sepolicy_rules();
        // boot 时 logd 已经在跑，直接注入
        int rc = auditpatch_inject_into_logd();
        if (rc != 0) {
            LOGW("auditpatch: boot-time inject failed rc=%d\n", rc);
        }
    }
}

// ============================================================================
// daemon 端 handler / CLI
// ============================================================================

void auditpatch_handler(int client) {
    if (client < 0)
        return;

    int req = read_int(client);
    int res = AuditPatchResponse::ERROR;

    switch (req) {
    case AuditPatchRequest::STATUS:
        res = auditpatch_enforced ? AuditPatchResponse::ENFORCED
                                  : AuditPatchResponse::NOT_ENFORCED;
        break;
    case AuditPatchRequest::ENABLE:
        res = auditpatch_enable();
        break;
    case AuditPatchRequest::DISABLE:
        res = auditpatch_disable();
        break;
    case AuditPatchRequest::INJECT:
        res = auditpatch_inject_into_logd() == 0
              ? AuditPatchResponse::OK
              : AuditPatchResponse::INJECT_FAILED;
        break;
    default:
        break;
    }
    write_int(client, res);
    close(client);
}

[[noreturn]] static void auditpatch_usage() {
    fprintf(stderr,
R"EOF(FoxMask AuditPatch CLI (independent, no ZygiskNext required)

Usage: magisk --auditpatch [action]
Actions:
   status    Return the enforcement status
   enable    Enable audit patch: persist DB flag, apply sepolicy rules,
             restart logd and inject libmagiskaudit.so
   disable   Disable audit patch: clear DB flag (hook active until
             next logd restart)
   inject    Re-inject libmagiskaudit.so into logd without changing state

)EOF");
    exit(1);
}

int auditpatch_cli(rust::Vec<rust::String> &args) {
    if (args.empty())
        auditpatch_usage();

    size_t argc = args.size();
    vector<const char *> argv;
    for (auto &a : args)
        argv.push_back(a.c_str());
    argv.push_back(nullptr);

    int req;
    if (argv[0] == "enable"sv)
        req = AuditPatchRequest::ENABLE;
    else if (argv[0] == "disable"sv)
        req = AuditPatchRequest::DISABLE;
    else if (argv[0] == "status"sv)
        req = AuditPatchRequest::STATUS;
    else if (argv[0] == "inject"sv)
        req = AuditPatchRequest::INJECT;
    else
        auditpatch_usage();

    int fd = connect_daemon(RequestCode::AUDITPATCH);
    if (fd < 0) {
        fprintf(stderr, "auditpatch: cannot connect to daemon\n");
        return 1;
    }
    write_int(fd, req);

    int res = read_int(fd);
    close(fd);

    if (res < 0 || res >= AuditPatchResponse::END)
        res = AuditPatchResponse::ERROR;

    switch (res) {
    case AuditPatchResponse::OK:
        fprintf(stderr, "AuditPatch: OK\n");
        break;
    case AuditPatchResponse::ENFORCED:
        fprintf(stderr, "AuditPatch: enforced\n");
        break;
    case AuditPatchResponse::NOT_ENFORCED:
        fprintf(stderr, "AuditPatch: not enforced\n");
        break;
    case AuditPatchResponse::INJECT_FAILED:
        fprintf(stderr, "AuditPatch: injection failed (see logcat)\n");
        break;
    case AuditPatchResponse::NOT_SUPPORTED:
        fprintf(stderr, "AuditPatch: not supported on this device\n");
        break;
    case AuditPatchResponse::ERROR:
        fprintf(stderr, "AuditPatch: daemon error\n");
        return 1;
    default:
        break;
    }
    return req == AuditPatchRequest::STATUS
           ? (res != AuditPatchResponse::ENFORCED)
           : (res != AuditPatchResponse::OK);
}
