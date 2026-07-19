#pragma once

#include <base.hpp>

// FoxMask 内置 AuditPatch 模块
//
// 独立功能，不依赖 ZygiskNext。原理：通过 ptrace 远程 dlopen
// libmagiskaudit.so 到 logd 进程，由该 .so 的构造函数用 lsplt
// PLT-hook logd 的 vasprintf，重写 SELinux audit 日志中的
// `tcontext=u:r:su:s0` / `tcontext=u:r:magisk:s0` 为
// `tcontext=u:r:priv_app:s0:c512,c768`，避免 root 痕迹被
// kernel audit (logd -k) 透传到 logcat。
//
// 同时附带两条 sepolicy.rule：
//   deny appdomain  cgroup_v2 dir search
//   deny app_zygote cgroup_v2 dir search
// 避免 cgroup v2 路径泄漏应用沙箱信息。

namespace AuditPatchRequest {
enum : int {
    STATUS,
    ENABLE,
    DISABLE,
    INJECT,    // 立即重启 logd 并注入（用于运行时切换）
    END
};
}

namespace AuditPatchResponse {
enum : int {
    OK,
    ENFORCED,
    NOT_ENFORCED,
    INJECT_FAILED,
    NOT_SUPPORTED,
    ERROR,
    END
};
}

// CLI 入口（magisk --auditpatch ARGS...）
int auditpatch_cli(rust::Vec<rust::String> &args);

// daemon 端请求处理
void auditpatch_handler(int client);

// 启动时初始化：若 DB 中 auditpatch=1，则重启 logd 并注入
void initialize_auditpatch();

// 返回当前是否已启用（DB 标志位）
bool auditpatch_enabled();

// 由 auditpatch.cpp 内部使用：将 libmagiskaudit.so 注入到 logd
// 返回 0 表示成功，非 0 表示失败码
int auditpatch_inject_into_logd();
