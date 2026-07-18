#include <sys/wait.h>
#include <sys/mount.h>

#include <core.hpp>

#include "deny.hpp"

using namespace std;

[[noreturn]] static void denylist_usage() {
    fprintf(stderr,
R"EOF(DenyList Config CLI

Usage: magisk --denylist [action [arguments...] ]
Actions:
   status          Return the enforcement status
   enable          Enable denylist enforcement
   disable         Disable denylist enforcement
   add PKG [PROC]  Add a new target to the denylist
   rm PKG [PROC]   Remove target(s) from the denylist
   ls              Print the current denylist
   exec CMDs...    Execute commands in isolated mount
                   namespace and do all unmounts

)EOF");
    exit(1);
}

[[noreturn]] static void sulist_usage() {
    fprintf(stderr,
R"EOF(SuList Config CLI (KitsuneMask-style whitelist)

Usage: magisk --sulist [action [arguments...] ]
Actions:
   status          Return the enforcement status
   enable          Enable SuList whitelist enforcement
   disable         Disable SuList whitelist enforcement
   add PKG [PROC]  Add a new target to the SuList whitelist
   rm PKG [PROC]   Remove target(s) from the SuList whitelist
   ls              Print the current SuList whitelist

)EOF");
    exit(1);
}

void denylist_handler(int client) {
    if (client < 0) {
        revert_unmount();
        return;
    }

    int req = read_int(client);
    int res = DenyResponse::ERROR;

    switch (req) {
    case DenyRequest::ENFORCE:
        res = enable_deny();
        break;
    case DenyRequest::DISABLE:
        res = disable_deny();
        break;
    case DenyRequest::ADD:
        res = add_list(client);
        break;
    case DenyRequest::REMOVE:
        res = rm_list(client);
        break;
    case DenyRequest::LIST:
        ls_list(client);
        return;
    case DenyRequest::STATUS:
        res = denylist_enforced ? DenyResponse::ENFORCED : DenyResponse::NOT_ENFORCED;
        break;
    default:
        // Unknown request code
        break;
    }
    write_int(client, res);
    close(client);
}

void sulist_handler(int client) {
    if (client < 0)
        return;

    int req = read_int(client);
    int res = DenyResponse::ERROR;

    switch (req) {
    case SuListRequest::ENFORCE:
        res = enable_sulist();
        break;
    case SuListRequest::DISABLE:
        res = disable_sulist();
        break;
    case SuListRequest::ADD:
        res = add_sulist(client);
        break;
    case SuListRequest::REMOVE:
        res = rm_sulist(client);
        break;
    case SuListRequest::LIST:
        ls_sulist(client);
        return;
    case SuListRequest::STATUS:
        res = sulist_enforced ? DenyResponse::ENFORCED : DenyResponse::NOT_ENFORCED;
        break;
    default:
        // Unknown request code
        break;
    }
    write_int(client, res);
    close(client);
}

int denylist_cli(rust::Vec<rust::String> &args) {
    if (args.empty())
        denylist_usage();

    // Convert rust strings into c strings
    size_t argc = args.size();
    std::vector<const char *> argv;
    ranges::transform(args, std::back_inserter(argv), [](rust::String &arg) { return arg.c_str(); });
    // End with nullptr
    argv.push_back(nullptr);

    int req;
    if (argv[0] == "enable"sv)
        req = DenyRequest::ENFORCE;
    else if (argv[0] == "disable"sv)
        req = DenyRequest::DISABLE;
    else if (argv[0] == "add"sv)
        req = DenyRequest::ADD;
    else if (argv[0] == "rm"sv)
        req = DenyRequest::REMOVE;
    else if (argv[0] == "ls"sv)
        req = DenyRequest::LIST;
    else if (argv[0] == "status"sv)
        req = DenyRequest::STATUS;
    else if (argv[0] == "exec"sv && argc > 1) {
        xunshare(CLONE_NEWNS);
        xmount(nullptr, "/", nullptr, MS_PRIVATE | MS_REC, nullptr);
        revert_unmount();
        execvp(argv[1], (char **) argv.data() + 1);
        exit(1);
    } else {
        denylist_usage();
    }

    // Send request
    int fd = connect_daemon(RequestCode::DENYLIST);
    write_int(fd, req);
    if (req == DenyRequest::ADD || req == DenyRequest::REMOVE) {
        write_string(fd, argv[1]);
        write_string(fd, argv[2] ? argv[2] : "");
    }

    // Get response
    int res = read_int(fd);
    if (res < 0 || res >= DenyResponse::END)
        res = DenyResponse::ERROR;
    switch (res) {
    case DenyResponse::NOT_ENFORCED:
        fprintf(stderr, "Denylist is not enforced\n");
        goto return_code;
    case DenyResponse::ENFORCED:
        fprintf(stderr, "Denylist is enforced\n");
        goto return_code;
    case DenyResponse::ITEM_EXIST:
        fprintf(stderr, "Target already exists in denylist\n");
        goto return_code;
    case DenyResponse::ITEM_NOT_EXIST:
        fprintf(stderr, "Target does not exist in denylist\n");
        goto return_code;
    case DenyResponse::NO_NS:
        fprintf(stderr, "The kernel does not support mount namespace\n");
        goto return_code;
    case DenyResponse::INVALID_PKG:
        fprintf(stderr, "Invalid package / process name\n");
        goto return_code;
    case DenyResponse::ERROR:
        fprintf(stderr, "deny: Daemon error\n");
        return -1;
    case DenyResponse::OK:
        break;
    default:
        __builtin_unreachable();
    }

    if (req == DenyRequest::LIST) {
        string out;
        for (;;) {
            read_string(fd, out);
            if (out.empty())
                break;
            printf("%s\n", out.data());
        }
    }

return_code:
    return req == DenyRequest::STATUS ? res != DenyResponse::ENFORCED : res != DenyResponse::OK;
}

int sulist_cli(rust::Vec<rust::String> &args) {
    if (args.empty())
        sulist_usage();

    // Convert rust strings into c strings
    size_t argc = args.size();
    std::vector<const char *> argv;
    ranges::transform(args, std::back_inserter(argv), [](rust::String &arg) { return arg.c_str(); });
    argv.push_back(nullptr);

    int req;
    if (argv[0] == "enable"sv)
        req = SuListRequest::ENFORCE;
    else if (argv[0] == "disable"sv)
        req = SuListRequest::DISABLE;
    else if (argv[0] == "add"sv)
        req = SuListRequest::ADD;
    else if (argv[0] == "rm"sv)
        req = SuListRequest::REMOVE;
    else if (argv[0] == "ls"sv)
        req = SuListRequest::LIST;
    else if (argv[0] == "status"sv)
        req = SuListRequest::STATUS;
    else {
        sulist_usage();
    }

    // Send request
    int fd = connect_daemon(RequestCode::SULIST);
    write_int(fd, req);
    if (req == SuListRequest::ADD || req == SuListRequest::REMOVE) {
        write_string(fd, argv[1]);
        write_string(fd, argv[2] ? argv[2] : "");
    }

    // Get response
    int res = read_int(fd);
    if (res < 0 || res >= DenyResponse::END)
        res = DenyResponse::ERROR;
    switch (res) {
    case DenyResponse::NOT_ENFORCED:
        fprintf(stderr, "SuList is not enforced\n");
        goto return_code;
    case DenyResponse::ENFORCED:
        fprintf(stderr, "SuList is enforced\n");
        goto return_code;
    case DenyResponse::ITEM_EXIST:
        fprintf(stderr, "Target already exists in sulist\n");
        goto return_code;
    case DenyResponse::ITEM_NOT_EXIST:
        fprintf(stderr, "Target does not exist in sulist\n");
        goto return_code;
    case DenyResponse::NO_NS:
        fprintf(stderr, "The kernel does not support mount namespace\n");
        goto return_code;
    case DenyResponse::INVALID_PKG:
        fprintf(stderr, "Invalid package / process name\n");
        goto return_code;
    case DenyResponse::ERROR:
        fprintf(stderr, "sulist: Daemon error\n");
        return -1;
    case DenyResponse::OK:
        break;
    default:
        __builtin_unreachable();
    }

    if (req == SuListRequest::LIST) {
        string out;
        for (;;) {
            read_string(fd, out);
            if (out.empty())
                break;
            printf("%s\n", out.data());
        }
    }

return_code:
    return req == SuListRequest::STATUS ? res != DenyResponse::ENFORCED : res != DenyResponse::OK;
}
