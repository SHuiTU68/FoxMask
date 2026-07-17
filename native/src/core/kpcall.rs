// KernelPatch supercall 接口实现
// 通过 syscall(45, ...) 调用 KernelPatch 的 supercall，实现 KPM 运行时管理。
// 命令行用法: kpcall <command> [superkey] [args...]
//   kpcall hello [key]                          — 检测 kpatch 是否已安装
//   kpcall kp-version [key]                     — 获取 kpatch 版本
//   kpcall k-version [key]                      — 获取内核版本
//   kpcall kpm-nums [key]                       — 获取已加载 KPM 数量
//   kpcall kpm-list [key]                       — 列出已加载 KPM 名称
//   kpcall kpm-info [key] <name>                — 获取 KPM 详细信息
//   kpcall kpm-load [key] <path> [args]         — 加载 KPM
//   kpcall kpm-unload [key] <name>              — 卸载 KPM
//   kpcall kpm-control [key] <name> <ctl_args>  — 控制 KPM
//
// superkey 可选：上游 KernelPatch 已剥离强 superkey 校验，root 调用者可使用 "su" 作为 key。
// 若未提供 key 参数，默认使用 "su"（要求调用者已被 su 授权）。

// Edition 2024 默认开启 unsafe_op_in_unsafe_fn 警告；本模块内 unsafe fn 体内的
// unsafe 操作（syscall/指针解引用等）语义上由调用者保证安全，统一 allow 以减少噪音。
#![allow(unsafe_op_in_unsafe_fn)]

use base::libc;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

// KernelPatch supercall syscall 号（劫持 __NR_truncate = 45）
const NR_SUPERCALL: libc::c_long = 45;

// supercall 命令码（来自 KernelPatch uapi/scdefs.h）
const SUPERCALL_HELLO: i64 = 0x1000;
const SUPERCALL_KERNELPATCH_VER: i64 = 0x1008;
const SUPERCALL_KERNEL_VER: i64 = 0x1009;
const SUPERCALL_KPM_LOAD: i64 = 0x1020;
const SUPERCALL_KPM_UNLOAD: i64 = 0x1021;
const SUPERCALL_KPM_CONTROL: i64 = 0x1022;
const SUPERCALL_KPM_NUMS: i64 = 0x1030;
const SUPERCALL_KPM_LIST: i64 = 0x1031;
const SUPERCALL_KPM_INFO: i64 = 0x1032;

// supercall hello 成功返回值
const SUPERCALL_HELLO_MAGIC: i64 = 0x11581158;

// KernelPatch 版本码（0.13.1 = 0x0D01）
// 用于 ver_and_cmd 的高 32 位
const KP_MAJOR: u32 = 0;
const KP_MINOR: u32 = 13;
const KP_PATCH: u32 = 1;

/// 构造 supercall 的 cmd 参数: (version_code << 32) | (0x1158 << 16) | (cmd & 0xFFFF)
fn ver_and_cmd(_key: &str, cmd: i64) -> libc::c_long {
    let version_code = (KP_MAJOR << 16) + (KP_MINOR << 8) + KP_PATCH;
    (((version_code as i64) << 32) | (0x1158 << 16) | (cmd & 0xFFFF)) as libc::c_long
}

/// 调用 supercall syscall
unsafe fn sc_call(key: &str, cmd: i64) -> libc::c_long {
    let key_c = CString::new(key).unwrap_or_default();
    let combined = ver_and_cmd(key, cmd);
    libc::syscall(NR_SUPERCALL, key_c.as_ptr(), combined)
}

unsafe fn sc_call1(key: &str, cmd: i64, arg1: *const libc::c_void) -> libc::c_long {
    let key_c = CString::new(key).unwrap_or_default();
    let combined = ver_and_cmd(key, cmd);
    libc::syscall(NR_SUPERCALL, key_c.as_ptr(), combined, arg1)
}

unsafe fn sc_call2(key: &str, cmd: i64, arg1: *const libc::c_void, arg2: usize) -> libc::c_long {
    let key_c = CString::new(key).unwrap_or_default();
    let combined = ver_and_cmd(key, cmd);
    libc::syscall(NR_SUPERCALL, key_c.as_ptr(), combined, arg1, arg2)
}

unsafe fn sc_call3(
    key: &str,
    cmd: i64,
    arg1: *const libc::c_void,
    arg2: *const libc::c_void,
    arg3: usize,
) -> libc::c_long {
    let key_c = CString::new(key).unwrap_or_default();
    let combined = ver_and_cmd(key, cmd);
    libc::syscall(NR_SUPERCALL, key_c.as_ptr(), combined, arg1, arg2, arg3)
}

unsafe fn sc_call4(
    key: &str,
    cmd: i64,
    arg1: *const libc::c_void,
    arg2: *const libc::c_void,
    arg3: *const libc::c_void,
    arg4: usize,
) -> libc::c_long {
    let key_c = CString::new(key).unwrap_or_default();
    let combined = ver_and_cmd(key, cmd);
    libc::syscall(NR_SUPERCALL, key_c.as_ptr(), combined, arg1, arg2, arg3, arg4)
}

/// 从 argv 获取参数字符串
unsafe fn get_arg(argv: *mut *mut c_char, index: i32) -> Option<String> {
    if index < 0 {
        return None;
    }
    let ptr = *argv.offset(index as isize);
    if ptr.is_null() {
        return None;
    }
    CStr::from_ptr(ptr).to_str().ok().map(|s| s.to_string())
}

fn print_usage() {
    eprintln!("Usage: kpcall <command> [superkey] [args...]");
    eprintln!("Commands:");
    eprintln!("  hello [key]                          Check if KernelPatch is installed");
    eprintln!("  kp-version [key]                     Get KernelPatch version");
    eprintln!("  k-version [key]                      Get kernel version");
    eprintln!("  kpm-nums [key]                       Get number of loaded KPMs");
    eprintln!("  kpm-list [key]                       List loaded KPM names");
    eprintln!("  kpm-info [key] <name>                Get KPM information");
    eprintln!("  kpm-load [key] <path> [args]         Load a KPM");
    eprintln!("  kpm-unload [key] <name>              Unload a KPM");
    eprintln!("  kpm-control [key] <name> <ctl_args>  Control a KPM");
    eprintln!("superkey is optional, defaults to \"su\" for root callers");
}

/// kpcall 主入口，作为 magisk 的 applet
pub fn kpcall_main(argc: i32, argv: *mut *mut c_char) -> i32 {
    unsafe { kpcall_main_impl(argc, argv) }
}

unsafe fn kpcall_main_impl(argc: i32, argv: *mut *mut c_char) -> i32 {
    if argc < 2 {
        print_usage();
        return 1;
    }

    // Edition 2024: unsafe fn 体内调用其他 unsafe fn 仍需显式 unsafe 块
    let (command, key, has_explicit_key) = unsafe {
        let command = match get_arg(argv, 1) {
            Some(c) => c,
            None => {
                eprintln!("kpcall: missing command");
                return 1;
            }
        };

        // superkey 可选：默认使用 "su"（root 调用者）
        // 若第二个参数看起来像 superkey（非命令专用参数），则使用它
        // 判断逻辑：hello/kp-version/k-version/kpm-nums 这些命令不需要额外参数，
        // 第二个参数就是 superkey；kpm-info/kpm-unload 等需要 name 参数，第二个参数可能是 key 也可能是 name。
        // 简化处理：所有命令统一接受 [key] 作为可选第二个参数，后续参数顺延。
        let key = get_arg(argv, 2)
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "su".to_string());

        // 若用户提供了 key（argv[2] 存在且非空），则后续参数从 index 3 开始
        // 否则从 index 2 开始（key 被默认为 "su"）
        let has_explicit_key = get_arg(argv, 2).map_or(false, |s| !s.is_empty());
        (command, key, has_explicit_key)
    };
    let arg_offset = if has_explicit_key { 3 } else { 2 };

    match command.as_str() {
        "hello" => {
            let ret = sc_call(&key, SUPERCALL_HELLO);
            if ret == SUPERCALL_HELLO_MAGIC {
                println!("KernelPatch is installed");
                0
            } else {
                eprintln!("KernelPatch not found (ret: {ret})");
                1
            }
        }

        "kp-version" => {
            let ret = sc_call(&key, SUPERCALL_KERNELPATCH_VER);
            if ret >= 0 {
                let ver = ret as u32;
                let major = (ver >> 16) & 0xFF;
                let minor = (ver >> 8) & 0xFF;
                let patch = ver & 0xFF;
                println!("{major}.{minor}.{patch}");
                0
            } else {
                eprintln!("Failed to get KernelPatch version (ret: {ret})");
                1
            }
        }

        "k-version" => {
            let ret = sc_call(&key, SUPERCALL_KERNEL_VER);
            if ret >= 0 {
                println!("{}", ret as u32);
                0
            } else {
                eprintln!("Failed to get kernel version (ret: {ret})");
                1
            }
        }

        "kpm-nums" => {
            let ret = sc_call(&key, SUPERCALL_KPM_NUMS);
            if ret >= 0 {
                println!("{ret}");
                0
            } else {
                eprintln!("Failed to get KPM count (ret: {ret})");
                1
            }
        }

        "kpm-list" => {
            let mut buf = vec![0u8; 4096];
            let ret = sc_call2(
                &key,
                SUPERCALL_KPM_LIST,
                buf.as_mut_ptr() as *const libc::c_void,
                buf.len(),
            );
            if ret >= 0 {
                let len = ret as usize;
                if len > 0 && len <= buf.len() {
                    let names = String::from_utf8_lossy(&buf[..len]);
                    print!("{names}");
                }
                0
            } else {
                eprintln!("Failed to list KPMs (ret: {ret})");
                1
            }
        }

        "kpm-info" => {
            let name = unsafe {
                match get_arg(argv, arg_offset) {
                    Some(n) => n,
                    None => {
                        eprintln!("kpcall kpm-info: missing module name");
                        return 1;
                    }
                }
            };
            let name_c = CString::new(name).unwrap_or_default();
            let mut buf = vec![0u8; 4096];
            let ret = sc_call3(
                &key,
                SUPERCALL_KPM_INFO,
                name_c.as_ptr() as *const libc::c_void,
                buf.as_mut_ptr() as *const libc::c_void,
                buf.len(),
            );
            if ret >= 0 {
                let len = ret as usize;
                if len > 0 && len <= buf.len() {
                    let info = String::from_utf8_lossy(&buf[..len]);
                    print!("{info}");
                }
                0
            } else {
                eprintln!("Failed to get KPM info (ret: {ret})");
                1
            }
        }

        "kpm-load" => {
            let path = unsafe {
                match get_arg(argv, arg_offset) {
                    Some(p) => p,
                    None => {
                        eprintln!("kpcall kpm-load: missing module path");
                        return 1;
                    }
                }
            };
            let args = unsafe { get_arg(argv, arg_offset + 1) }.unwrap_or_default();
            let path_c = CString::new(path).unwrap_or_default();
            let args_c = CString::new(args).unwrap_or_default();
            // sc_kpm_load(path, args) — 两个指针参数
            let ret = sc_call3(
                &key,
                SUPERCALL_KPM_LOAD,
                path_c.as_ptr() as *const libc::c_void,
                args_c.as_ptr() as *const libc::c_void,
                0,
            );
            if ret == 0 {
                println!("KPM loaded successfully");
                0
            } else {
                eprintln!("Failed to load KPM (ret: {ret})");
                1
            }
        }

        "kpm-unload" => {
            let name = unsafe {
                match get_arg(argv, arg_offset) {
                    Some(n) => n,
                    None => {
                        eprintln!("kpcall kpm-unload: missing module name");
                        return 1;
                    }
                }
            };
            let name_c = CString::new(name).unwrap_or_default();
            // sc_kpm_unload(name) — 一个指针参数 + 一个空指针占位
            let ret = sc_call2(
                &key,
                SUPERCALL_KPM_UNLOAD,
                name_c.as_ptr() as *const libc::c_void,
                0,
            );
            if ret == 0 {
                println!("KPM unloaded successfully");
                0
            } else {
                eprintln!("Failed to unload KPM (ret: {ret})");
                1
            }
        }

        "kpm-control" => {
            let name = unsafe {
                match get_arg(argv, arg_offset) {
                    Some(n) => n,
                    None => {
                        eprintln!("kpcall kpm-control: missing module name");
                        return 1;
                    }
                }
            };
            let ctl_args = unsafe {
                match get_arg(argv, arg_offset + 1) {
                    Some(a) => a,
                    None => {
                        eprintln!("kpcall kpm-control: missing control args");
                        return 1;
                    }
                }
            };
            let name_c = CString::new(name).unwrap_or_default();
            let ctl_c = CString::new(ctl_args).unwrap_or_default();
            let mut buf = vec![0u8; 4096];
            // sc_kpm_control(name, ctl_args, buf, buf_len) — 3 个指针 + 1 个 size
            let ret = sc_call4(
                &key,
                SUPERCALL_KPM_CONTROL,
                name_c.as_ptr() as *const libc::c_void,
                ctl_c.as_ptr() as *const libc::c_void,
                buf.as_mut_ptr() as *const libc::c_void,
                buf.len(),
            );
            if ret >= 0 {
                let len = ret as usize;
                if len > 0 && len <= buf.len() {
                    let info = String::from_utf8_lossy(&buf[..len]);
                    print!("{info}");
                }
                0
            } else {
                eprintln!("Failed to control KPM (ret: {ret})");
                1
            }
        }

        _ => {
            eprintln!("kpcall: unknown command '{command}'");
            print_usage();
            1
        }
    }
}
